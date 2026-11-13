use base64::Engine;
use serde::Deserialize;
use serde_json::{Value, json};
#[cfg(not(target_os = "linux"))]
use std::{
    borrow::Cow,
    sync::atomic::{AtomicBool, Ordering},
};
use std::{
    collections::HashMap,
    io::{self, BufRead, Write},
    net::{Shutdown, TcpListener},
    sync::Arc,
    thread,
};
use tao::{
    event::{Event, WindowEvent},
    event_loop::{ControlFlow, EventLoopBuilder},
    window::WindowBuilder,
};
#[cfg(not(target_os = "linux"))]
use wry::{NewWindowResponse, PermissionResponse, ProxyConfig, ProxyEndpoint, WebViewBuilder};

const SCHEME: &str = "messenger-artifact";
const MAX_BYTES: usize = 96 * 1024 * 1024;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Configuration {
    url: String,
    browser_url: String,
    secret: String,
    resources: HashMap<String, Resource>,
    title: String,
}
#[derive(Deserialize)]
struct Resource {
    mime: String,
    body: String,
}
#[derive(Debug)]
enum Command {
    Reply(String),
    Focus,
    Close,
}

fn emit(value: Value) {
    let mut stdout = io::stdout().lock();
    let _ = serde_json::to_writer(&mut stdout, &value);
    let _ = stdout.write_all(b"\n");
    let _ = stdout.flush();
}

fn main() {
    if let Err(error) = run() {
        emit(json!({"type":"error", "detail":error.to_string()}));
        eprintln!("Artifact host: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    // Never weaken the engine's process sandbox, including through inherited flags.
    for name in [
        "WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS",
        "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
        "WEBKIT_INSPECTOR_SERVER",
        "WEBVIEW2_PIPE_FOR_SCRIPT_DEBUGGER",
        "WEBVIEW2_WAIT_FOR_SCRIPT_DEBUGGER",
    ] {
        if std::env::var(name).is_ok_and(|v| !v.is_empty() && v != "0") {
            return Err(format!("Unsafe inherited webview configuration: {name}").into());
        }
    }
    let mut input = io::stdin().lock();
    let mut line = String::new();
    input.read_line(&mut line)?;
    if line.len() > MAX_BYTES {
        return Err("Artifact package exceeds the host limit".into());
    }
    let config: Configuration = serde_json::from_str(&line)?;
    if config.secret.len() != 64 || !config.secret.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("Invalid bridge capability".into());
    }
    let expected = url::Url::parse(&config.url)?;
    if expected.scheme() != SCHEME
        || expected.path() != "/host.html"
        || expected.host_str().is_none()
    {
        return Err("Invalid artifact origin".into());
    }
    let host = expected.host_str().unwrap().to_string();
    let mut total = 0usize;
    let mut resources = HashMap::new();
    for (path, resource) in config.resources {
        if !valid_resource_path(&path) {
            return Err("Invalid artifact resource path".into());
        }
        let body = base64::engine::general_purpose::STANDARD.decode(resource.body)?;
        total = total
            .checked_add(body.len())
            .ok_or("Artifact is too large")?;
        if total > 64 * 1024 * 1024 {
            return Err("Artifact resources exceed 64 MiB".into());
        }
        resources.insert(path, (resource.mime, body));
    }
    let resources = Arc::new(resources);
    drop(input);

    let event_loop = EventLoopBuilder::<Command>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    thread::spawn(move || {
        for line in io::stdin().lock().lines() {
            let Ok(line) = line else { break };
            if line.len() > 1_048_576 {
                continue;
            }
            let Ok(message) = serde_json::from_str::<Value>(&line) else {
                continue;
            };
            match message["type"].as_str() {
                Some("reply") => {
                    if let Some(reply) = message["payload"].as_str() {
                        let _ = proxy.send_event(Command::Reply(reply.to_owned()));
                    }
                }
                Some("focus") => {
                    let _ = proxy.send_event(Command::Focus);
                }
                Some("close") => break,
                _ => (),
            }
        }
        let _ = proxy.send_event(Command::Close);
    });

    let window = WindowBuilder::new()
        .with_title(config.title)
        .build(&event_loop)?;
    // A deny-only proxy is defense in depth alongside CSP and navigation policy.
    // It never forwards bytes or resolves a destination; package custom resources
    // bypass the network stack. The OS web content process sandbox stays enabled.
    let deny_proxy = TcpListener::bind(("127.0.0.1", 0))?;
    let proxy_port = deny_proxy.local_addr()?.port();
    thread::spawn(move || {
        for stream in deny_proxy.incoming().flatten() {
            let _ = stream.shutdown(Shutdown::Both);
        }
    });
    #[cfg(target_os = "linux")]
    let related_view = {
        use webkit2gtk::{
            DownloadExt, SecurityManagerExt, URISchemeRequestExt, URISchemeResponseExt,
            WebContextExt, WebsiteDataManagerExt,
        };
        let context = webkit2gtk::WebContext::new_ephemeral();
        // Configure before constructing even an unloaded WebView: WebKitGTK may
        // spawn its web process during construction, not only at the first load.
        context.set_sandbox_enabled(true);
        if !context.is_sandbox_enabled() {
            return Err("WebKit process sandbox unavailable".into());
        }
        let manager = context
            .security_manager()
            .ok_or("WebKit security manager unavailable")?;
        manager.register_uri_scheme_as_secure(SCHEME);
        manager.register_uri_scheme_as_cors_enabled(SCHEME);
        let mut proxy = webkit2gtk::NetworkProxySettings::new(
            Some(&format!("http://127.0.0.1:{proxy_port}")),
            &[],
        );
        context
            .website_data_manager()
            .ok_or("WebKit data manager unavailable")?
            .set_network_proxy_settings(webkit2gtk::NetworkProxyMode::Custom, Some(&mut proxy));
        let resources = resources.clone();
        let host = host.clone();
        context.connect_download_started(|_, download| download.cancel());
        context.register_uri_scheme(SCHEME, move |request| {
            let uri = request.uri().and_then(|v| url::Url::parse(&v).ok());
            let resource = uri
                .as_ref()
                .filter(|uri| {
                    uri.scheme() == SCHEME
                        && uri.host_str() == Some(host.as_str())
                        && uri.query().is_none()
                        && request.http_method().is_some_and(|m| m == "GET")
                })
                .and_then(|uri| resources.get(uri.path()));
            let bytes = resource.map(|r| r.1.as_slice()).unwrap_or(b"Blocked");
            let input = webkit2gtk::gio::MemoryInputStream::from_bytes(
                &webkit2gtk::glib::Bytes::from(bytes),
            );
            let response = webkit2gtk::URISchemeResponse::new(&input, bytes.len() as i64);
            response.set_status(if resource.is_some() { 200 } else { 403 }, None);
            response.set_content_type(resource.map(|r| r.0.as_str()).unwrap_or("text/plain"));
            let headers = soup::MessageHeaders::new(soup::MessageHeadersType::Response);
            for (name, value) in [
                ("Access-Control-Allow-Origin", "*"),
                ("Cache-Control", "no-store"),
                ("X-Content-Type-Options", "nosniff"),
                ("Referrer-Policy", "no-referrer"),
            ] {
                headers.append(name, value);
            }
            response.set_http_headers(headers);
            request.finish_with_response(&response);
        });
        webkit2gtk::WebView::builder().web_context(&context).build()
    };
    let secret = config.secret;
    #[cfg(not(target_os = "linux"))]
    let builder = {
        let ipc_url = config.browser_url.clone();
        let first_navigation = Arc::new(AtomicBool::new(false));
        let navigation_url = config.browser_url.clone();
        let initial_url = config.url.clone();
        let ready_url = config.browser_url.clone();
        WebViewBuilder::new()
            .with_incognito(true)
            .with_devtools(false)
            .with_clipboard(false)
            .with_drag_drop_handler(|_| true)
            .with_permission_handler(|_| PermissionResponse::Deny)
            .with_download_started_handler(|_, _| false)
            .with_new_window_req_handler(|_, _| NewWindowResponse::Deny)
            .with_proxy_config(ProxyConfig::Http(ProxyEndpoint {
                host: "127.0.0.1".into(),
                port: proxy_port.to_string(),
            }))
            .with_navigation_handler(move |url| {
                if url == "about:srcdoc" {
                    return true;
                }
                (url == navigation_url || url == initial_url)
                    && !first_navigation.swap(true, Ordering::SeqCst)
            })
            .with_custom_protocol(SCHEME.into(), move |_, request| {
                let uri = request.uri();
                let allowed = uri.scheme_str() == Some(SCHEME)
                    && uri.host() == Some(host.as_str())
                    && request.method() == "GET"
                    && uri.query().is_none();
                let resource = allowed.then(|| resources.get(uri.path())).flatten();
                let mut response = wry::http::Response::builder()
                    .status(if resource.is_some() { 200 } else { 403 })
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Cache-Control", "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Referrer-Policy", "no-referrer")
                    .header(
                        "Permissions-Policy",
                        "camera=(), microphone=(), geolocation=(), usb=(), payment=()",
                    );
                let body = if let Some((mime, body)) = resource {
                    response = response.header("Content-Type", mime.as_str());
                    body.clone()
                } else {
                    b"Blocked".to_vec()
                };
                response.body(Cow::Owned(body)).unwrap()
            })
            .with_ipc_handler(move |request| {
                // On WebKitGTK Wry exposes the main URI for all frames. The independent
                // capability is held only in the trusted wrapper's closure, never in
                // the child SDK or nonce. This also protects WebView2 and WKWebView.
                if request.uri().to_string() != ipc_url || request.body().len() > 1_048_576 {
                    return;
                }
                let Ok(envelope) = serde_json::from_str::<Value>(request.body()) else {
                    return;
                };
                if envelope["secret"].as_str() != Some(secret.as_str()) {
                    return;
                }
                let Some(payload) = envelope["payload"].as_str() else {
                    return;
                };
                let Ok(message) = serde_json::from_str::<Value>(payload) else {
                    return;
                };
                if !message["id"].is_string()
                    || !matches!(
                        message["method"].as_str(),
                        Some("getState" | "setState" | "fetch")
                    )
                {
                    return;
                }
                emit(json!({"type":"request", "payload":payload}));
            })
            .with_on_page_load_handler(move |event, url| {
                if matches!(event, wry::PageLoadEvent::Finished) && url == ready_url {
                    emit(json!({"type":"ready", "engine":system_engine()}));
                }
            })
    };
    #[cfg(target_os = "windows")]
    let builder = {
        use wry::WebViewBuilderExtWindows;
        builder
            .with_https_scheme(true)
            .with_default_context_menus(false)
    };
    #[cfg(target_os = "linux")]
    let webview = LinuxView::new(
        related_view,
        &window,
        config.browser_url.clone(),
        config.url.clone(),
        secret,
    )?;
    #[cfg(not(target_os = "linux"))]
    let webview = builder.build(&window)?;
    #[cfg(target_os = "linux")]
    {
        use webkit2gtk::{
            FileChooserRequestExt, SecurityManagerExt, SettingsExt, WebContextExt, WebViewExt,
        };
        let native = webview.0.clone();
        let context = native.context().ok_or("WebKit context unavailable")?;
        // Verify Wry retained the preconfigured sandboxed ephemeral context.
        if !context.is_sandbox_enabled() || !context.is_ephemeral() {
            return Err("A sandboxed ephemeral WebKit context is required".into());
        }
        let manager = context
            .security_manager()
            .ok_or("WebKit security manager unavailable")?;
        manager.register_uri_scheme_as_cors_enabled(SCHEME);
        if let Some(settings) = native.settings() {
            settings
                .set_hardware_acceleration_policy(webkit2gtk::HardwareAccelerationPolicy::Never);
            settings.set_enable_developer_extras(false);
            settings.set_enable_webrtc(false);
            settings.set_enable_media_stream(false);
            settings.set_enable_dns_prefetching(false);
            settings.set_enable_html5_database(false);
            settings.set_enable_html5_local_storage(false);
        }
        {
            use gtk::prelude::WidgetExt;
            use tao::platform::unix::WindowExtUnix;
            native.set_hexpand(true);
            native.set_vexpand(true);
            native.show_all();
            window.gtk_window().show_all();
        }
        native.connect_context_menu(|_, _, _, _| true);
        native.connect_run_file_chooser(|_, request| {
            request.cancel();
            true
        });
        native.connect_web_process_terminated(|_, reason| {
            emit(json!({"type":"error", "detail":format!("WebKit content process stopped: {reason:?}")}));
        });
    }
    webview.load_url(&config.url)?;
    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        match event {
            Event::UserEvent(Command::Focus) => {
                window.set_visible(true);
                window.set_minimized(false);
                window.set_focus();
            }
            Event::UserEvent(Command::Reply(reply)) => {
                if let Ok(literal) = serde_json::to_string(&reply) {
                    let _ = webview
                        .evaluate_script(&format!("window.__artifactNativeReply?.({literal})"));
                }
            }
            Event::UserEvent(Command::Close)
            | Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } => {
                *control_flow = ControlFlow::Exit;
            }
            _ => (),
        }
    });
}

#[cfg(target_os = "linux")]
struct LinuxView(webkit2gtk::WebView);
#[cfg(target_os = "linux")]
impl LinuxView {
    fn new(
        native: webkit2gtk::WebView,
        window: &tao::window::Window,
        browser_url: String,
        initial_url: String,
        secret: String,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        use gtk::prelude::*;
        use tao::platform::unix::WindowExtUnix;
        use webkit2gtk::{
            NavigationPolicyDecisionExt, PermissionRequestExt, PolicyDecisionExt, URIRequestExt,
            UserContentManagerExt, WebViewExt,
        };
        let manager = native
            .user_content_manager()
            .ok_or("WebKit content manager unavailable")?;
        let ipc_url = browser_url.clone();
        let source = native.clone();
        manager.connect_script_message_received(Some("artifact"), move |_, result| {
            if source.uri().as_deref() != Some(&ipc_url) {
                return;
            }
            let Some(value) = result.js_value() else {
                return;
            };
            let raw = value.to_string();
            if raw.len() > 1_048_576 {
                return;
            }
            let Ok(envelope) = serde_json::from_str::<Value>(&raw) else {
                return;
            };
            if envelope["secret"].as_str() != Some(&secret) {
                return;
            }
            let Some(payload) = envelope["payload"].as_str() else {
                return;
            };
            let Ok(message) = serde_json::from_str::<Value>(payload) else {
                return;
            };
            if message["id"].is_string()
                && matches!(
                    message["method"].as_str(),
                    Some("getState" | "setState" | "fetch")
                )
            {
                emit(json!({"type":"request", "payload":payload}));
            }
        });
        manager.register_script_message_handler("artifact");
        manager.add_script(&webkit2gtk::UserScript::new(
            "Object.defineProperty(window,'ipc',{value:Object.freeze({postMessage:s=>window.webkit.messageHandlers.artifact.postMessage(s)})});",
            webkit2gtk::UserContentInjectedFrames::TopFrame, webkit2gtk::UserScriptInjectionTime::Start, &[], &[]));
        let allowed = std::rc::Rc::new(std::cell::Cell::new(false));
        native.connect_decide_policy(move |_, decision, kind| {
            if kind == webkit2gtk::PolicyDecisionType::NavigationAction
                || kind == webkit2gtk::PolicyDecisionType::NewWindowAction
            {
                let action = decision
                    .downcast_ref::<webkit2gtk::NavigationPolicyDecision>()
                    .and_then(|d| d.navigation_action())
                    .and_then(|a| a.request())
                    .and_then(|r| r.uri())
                    .map(|u| u.to_string())
                    .unwrap_or_default();
                let valid = kind == webkit2gtk::PolicyDecisionType::NavigationAction
                    && (action == "about:srcdoc"
                        || (action == browser_url || action == initial_url)
                            && !allowed.replace(true));
                if valid {
                    decision.use_();
                } else {
                    decision.ignore();
                }
                return true;
            }
            false
        });
        native.connect_create(|_, _| None);
        native.connect_permission_request(|_, request| {
            request.deny();
            true
        });
        native.connect_load_changed(|_, event| {
            if event == webkit2gtk::LoadEvent::Finished {
                emit(json!({"type":"ready", "engine":system_engine()}));
            }
        });
        native.connect_load_failed(|_, _, _, error| {
            emit(json!({"type":"error", "detail":error.to_string()}));
            true
        });
        let container = window.default_vbox().ok_or("GTK container unavailable")?;
        container.pack_start(&native, true, true, 0);
        native.show_all();
        Ok(Self(native))
    }
    fn load_url(&self, url: &str) -> Result<(), Box<dyn std::error::Error>> {
        use webkit2gtk::WebViewExt;
        self.0.load_uri(url);
        Ok(())
    }
    fn evaluate_script(&self, script: &str) -> Result<(), ()> {
        use webkit2gtk::WebViewExt;
        self.0.evaluate_javascript(
            script,
            None,
            None,
            None::<&webkit2gtk::gio::Cancellable>,
            |_| {},
        );
        Ok(())
    }
}

fn valid_resource_path(path: &str) -> bool {
    path.starts_with('/')
        && path.len() > 1
        && path[1..]
            .split('/')
            .all(|p| !p.is_empty() && p != "." && p != "..")
        && path
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"/_.-".contains(&b))
}
fn system_engine() -> &'static str {
    if cfg!(target_os = "linux") {
        "WebKitGTK (process sandbox enabled)"
    } else if cfg!(target_os = "windows") {
        "WebView2"
    } else {
        "WKWebView"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_literal_package_paths_are_servable() {
        assert!(valid_resource_path("/package/app.mjs"));
        for path in [
            "/../host.html",
            "/package/%2e%2e/host.html",
            "//host.html",
            "/package/a?b",
            "file:///tmp/a",
        ] {
            assert!(!valid_resource_path(path), "{path}");
        }
    }
}
