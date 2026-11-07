//! Loopback bridge between ACP byte streams and the signature-protected Runtime host.
use super::*;
use crate::external_acp::{Process, Profile};
use serde_json::json;
use tokio::io::{AsyncRead, AsyncWriteExt, DuplexStream, ReadBuf, ReadHalf};
use tokio_util::codec::{FramedRead, LinesCodec};

pub(crate) struct Pipes {
    stdin: Option<mpsc::Receiver<String>>,
    stdout: mpsc::Sender<String>,
    cancel: CancellationToken,
    error: Arc<std::sync::Mutex<Option<String>>>,
}

pub(crate) type Processes = Mutex<HashMap<Uuid, Pipes>>;

pub(crate) async fn spawn(
    state: &ApiState,
    profile: &Profile,
    cwd: &std::path::Path,
) -> Result<Process, ApiError> {
    let id = Uuid::new_v4();
    let lifetime = state.shutdown.child_token();
    let (client, platform) = tokio::io::duplex(65_536);
    let (stdout, stdin) = tokio::io::split(client);
    let error = Arc::new(std::sync::Mutex::new(None));
    let (to_platform, from_sdk) = mpsc::channel(32);
    let (to_sdk, mut from_platform) = mpsc::channel::<String>(32);
    state.acp_processes.lock().await.insert(
        id,
        Pipes {
            stdin: Some(from_sdk),
            stdout: to_sdk,
            cancel: lifetime.clone(),
            error: error.clone(),
        },
    );
    let request = json!({"processId":id,"profile":profile,"cwd":cwd});
    if state.events.send(CoreEvent::AcpProcess(request)).is_err() {
        state.acp_processes.lock().await.remove(&id);
        return Err(ApiError::internal(
            "The Android Runtime host is not connected.",
        ));
    }
    let processes = state.acp_processes.clone();
    let cancel = lifetime.clone();
    let (read, mut write) = tokio::io::split(platform);
    tokio::spawn(async move {
        let mut lines = FramedRead::new(read, LinesCodec::new_with_max_length(192_000));
        let outgoing = async {
            while let Some(Ok(line)) = lines.next().await {
                if to_platform.send(line).await.is_err() {
                    break;
                }
            }
        };
        let incoming = async {
            while let Some(line) = from_platform.recv().await {
                if write.write_all(line.as_bytes()).await.is_err()
                    || write.write_all(b"\n").await.is_err()
                {
                    break;
                }
            }
        };
        tokio::select! {
            _ = outgoing => {},
            _ = incoming => {},
            _ = cancel.cancelled() => {},
        }
        cancel.cancel();
        processes.lock().await.remove(&id);
    });
    Ok(Process {
        stdin: Box::new(stdin),
        stdout: Box::new(RuntimeOutput { stdout, error }),
        _lifetime: Box::new(lifetime.drop_guard()),
    })
}

struct RuntimeOutput {
    stdout: ReadHalf<DuplexStream>,
    error: Arc<std::sync::Mutex<Option<String>>>,
}

impl AsyncRead for RuntimeOutput {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        let filled = buf.filled().len();
        let polled = Pin::new(&mut self.stdout).poll_read(cx, buf);
        if matches!(polled, Poll::Ready(Ok(()))) && buf.filled().len() == filled {
            if let Some(error) = self.error.lock().unwrap().take() {
                return Poll::Ready(Err(std::io::Error::other(error)));
            }
        }
        polled
    }
}

pub(crate) async fn input(
    State(state): State<ApiState>,
    Path(id): Path<Uuid>,
) -> Result<Sse<impl Stream<Item = Result<Event, Infallible>>>, ApiError> {
    let mut processes = state.acp_processes.lock().await;
    let pipes = processes
        .get_mut(&id)
        .ok_or_else(|| ApiError::not_found("ACP process"))?;
    let stream = pipes
        .stdin
        .take()
        .ok_or_else(|| ApiError::BadRequest("process input already attached".into()))?;
    let guard = pipes.cancel.clone().drop_guard();
    let stream = tokio_stream::wrappers::ReceiverStream::new(stream).map(move |line| {
        let _guard = &guard;
        Ok(Event::default().data(json!({"line":line}).to_string()))
    });
    Ok(Sse::new(ShutdownStream::new(stream, &pipes.cancel)).keep_alive(KeepAlive::default()))
}

#[derive(Deserialize)]
pub(crate) struct Output {
    line: Option<String>,
    error: Option<String>,
}

pub(crate) async fn output(
    State(state): State<ApiState>,
    Path(id): Path<Uuid>,
    Json(output): Json<Output>,
) -> Result<StatusCode, ApiError> {
    let (stdout, cancel, error) = {
        let processes = state.acp_processes.lock().await;
        let pipes = processes
            .get(&id)
            .ok_or_else(|| ApiError::not_found("ACP process"))?;
        (
            pipes.stdout.clone(),
            pipes.cancel.clone(),
            pipes.error.clone(),
        )
    };
    if let Some(line) = output.line {
        if line.len() > 192_000 || line.contains(['\n', '\r']) {
            cancel.cancel();
            return Err(ApiError::BadRequest("invalid ACP process line".into()));
        }
        stdout.send(line).await.map_err(ApiError::internal)?;
    } else {
        *error.lock().unwrap() = output.error.map(|error| crate::control::short(&error, 500));
        cancel.cancel();
    }
    Ok(StatusCode::NO_CONTENT)
}
