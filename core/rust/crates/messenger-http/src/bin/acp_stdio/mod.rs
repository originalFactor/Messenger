use std::{net::SocketAddr, path::Path};

use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_tungstenite::tungstenite::{client::IntoClientRequest, Message};

#[derive(Deserialize)]
struct Descriptor {
    address: SocketAddr,
    token: String,
}

pub async fn connect(path: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let metadata = std::fs::symlink_metadata(path)?;
    if !metadata.is_file() || metadata.len() > 4096 {
        return Err("invalid ACP connection descriptor".into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o077 != 0 {
            return Err("ACP connection descriptor must be private to the current user".into());
        }
    }
    let descriptor: Descriptor = serde_json::from_slice(&std::fs::read(path)?)?;
    if !descriptor.address.ip().is_loopback() {
        return Err("ACP connection must use loopback".into());
    }
    let mut request = format!("ws://{}/api/v1/acp", descriptor.address).into_client_request()?;
    request.headers_mut().insert(
        "Authorization",
        format!("Bearer {}", descriptor.token).parse()?,
    );
    let (socket, _) = tokio_tungstenite::connect_async(request).await?;
    let (mut write, mut read) = socket.split();
    let input = async {
        let mut stdin = tokio::io::stdin();
        let mut bytes = [0; 8192];
        loop {
            let count = stdin.read(&mut bytes).await?;
            if count == 0 {
                break;
            }
            write.send(Message::Binary(bytes[..count].to_vec())).await?;
        }
        Ok::<_, Box<dyn std::error::Error>>(())
    };
    let output = async {
        let mut stdout = tokio::io::stdout();
        while let Some(message) = read.next().await {
            match message? {
                Message::Binary(bytes) => stdout.write_all(&bytes).await?,
                Message::Text(text) => stdout.write_all(text.as_bytes()).await?,
                Message::Close(_) => break,
                _ => continue,
            }
            stdout.flush().await?;
        }
        Ok::<_, Box<dyn std::error::Error>>(())
    };
    tokio::select! { result = input => result, result = output => result }
}
