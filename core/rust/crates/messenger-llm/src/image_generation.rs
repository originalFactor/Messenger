//! Explicit Images API adapter. Never infers support from chat modalities and never retries.
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::{collections::BTreeSet, io::Cursor, time::Duration};

pub const MAX_IMAGE_BYTES: usize = 20 * 1024 * 1024;
pub const MAX_RESPONSE_BYTES: usize = 112 * 1024 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum ImageError {
    #[error("invalid image request: {0}")]
    Invalid(String),
    #[error("provider HTTP {status}: {detail}")]
    Http { status: u16, detail: String },
    #[error("outcome unknown; do not automatically resubmit: {0}")]
    Unknown(String),
}
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ImageOutput {
    pub b64_json: Option<String>,
    pub url: Option<String>,
    pub revised_prompt: Option<String>,
}
#[derive(Debug, Deserialize, Serialize)]
pub struct GenerationResponse {
    pub data: Vec<ImageOutput>,
    pub usage: Option<Value>,
}

pub fn content_hash(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

/// Fully decode supported raster formats with bounded allocation and dimensions.
pub fn validate_image(bytes: &[u8]) -> Result<(String, u32, u32), ImageError> {
    if bytes.is_empty() || bytes.len() > MAX_IMAGE_BYTES {
        return Err(ImageError::Invalid("image byte limit exceeded".into()));
    }
    let format = image::guess_format(bytes).map_err(|e| ImageError::Invalid(e.to_string()))?;
    let mime = match format {
        image::ImageFormat::Png => "image/png",
        image::ImageFormat::Jpeg => "image/jpeg",
        image::ImageFormat::WebP => "image/webp",
        _ => {
            return Err(ImageError::Invalid(
                "only PNG, JPEG and WebP images supported".into(),
            ))
        }
    };
    let mut reader = image::ImageReader::with_format(Cursor::new(bytes), format);
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(8192);
    limits.max_image_height = Some(8192);
    limits.max_alloc = Some(128 * 1024 * 1024);
    reader.limits(limits);
    let decoded = reader
        .decode()
        .map_err(|e| ImageError::Invalid(format!("image decoding failed: {e}")))?;
    if decoded.width() == 0
        || decoded.height() == 0
        || u64::from(decoded.width()) * u64::from(decoded.height()) > 32_000_000
    {
        return Err(ImageError::Invalid("image dimensions exceed limit".into()));
    }
    Ok((mime.into(), decoded.width(), decoded.height()))
}

pub async fn generate(
    base_url: &str,
    api_key: &str,
    model: &str,
    prompt: &str,
    parameters: &Map<String, Value>,
    supported: &BTreeSet<String>,
) -> Result<GenerationResponse, ImageError> {
    if model.trim().is_empty() || prompt.trim().is_empty() || prompt.len() > 32000 {
        return Err(ImageError::Invalid(
            "model and bounded prompt required".into(),
        ));
    }
    let allowed = [
        "n",
        "size",
        "quality",
        "style",
        "response_format",
        "background",
        "output_format",
        "output_compression",
        "moderation",
    ];
    for key in parameters.keys() {
        if !supported.contains(key) || !allowed.contains(&key.as_str()) {
            return Err(ImageError::Invalid(format!("unsupported parameter {key}")));
        }
    }
    if let Some(n) = parameters.get("n") {
        if !matches!(n.as_u64(), Some(1..=4)) {
            return Err(ImageError::Invalid("n must be 1..4".into()));
        }
    }
    let mut body = parameters.clone();
    body.insert("model".into(), model.into());
    body.insert("prompt".into(), prompt.into());
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(180))
        .build()
        .map_err(|e| ImageError::Invalid(e.to_string()))?;
    let endpoint = format!("{}/images/generations", base_url.trim_end_matches('/'));
    let url = reqwest::Url::parse(&endpoint).map_err(|e| ImageError::Invalid(e.to_string()))?;
    if !matches!(url.scheme(), "https" | "http")
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(ImageError::Invalid("invalid provider URL".into()));
    }
    let response = client
        .post(url)
        .bearer_auth(api_key)
        .json(&body)
        .send()
        .await
        .map_err(|e| ImageError::Unknown(e.to_string()))?;
    let status = response.status();
    let limit = if status.is_success() {
        MAX_RESPONSE_BYTES
    } else {
        64 * 1024
    };
    let mut bytes = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| ImageError::Unknown(e.to_string()))?;
        if bytes.len() + chunk.len() > limit {
            return Err(ImageError::Unknown(
                "provider response exceeds limit".into(),
            ));
        }
        bytes.extend_from_slice(&chunk);
    }
    if !status.is_success() {
        return Err(ImageError::Http {
            status: status.as_u16(),
            detail: String::from_utf8_lossy(&bytes).into_owned(),
        });
    }
    let output: GenerationResponse = serde_json::from_slice(&bytes)
        .map_err(|e| ImageError::Unknown(format!("invalid provider response: {e}")))?;
    if output.data.is_empty() || output.data.len() > 4 {
        return Err(ImageError::Unknown(
            "provider returned invalid image count".into(),
        ));
    }
    for item in &output.data {
        if item.b64_json.is_some() == item.url.is_some() {
            return Err(ImageError::Unknown(
                "each image must have exactly one base64 or URL output".into(),
            ));
        }
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::{
        matchers::{method, path},
        Mock, MockServer, ResponseTemplate,
    };
    #[tokio::test]
    async fn images_endpoint_and_provider_error_are_not_retried() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/images/generations"))
            .respond_with(ResponseTemplate::new(429).set_body_string("quota exhausted"))
            .expect(1)
            .mount(&server)
            .await;
        let result = generate(
            &format!("{}/v1", server.uri()),
            "secret",
            "image-model",
            "a tree",
            &Map::new(),
            &BTreeSet::new(),
        )
        .await;
        assert!(matches!(result, Err(ImageError::Http { status: 429, .. })));
    }
    #[tokio::test]
    async fn parses_multiple_outputs_and_usage() {
        let server = MockServer::start().await;
        Mock::given(method("POST")).respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({"data":[{"b64_json":"AAAA"},{"url":"https://example.org/image.png"}],"usage":{"images":2}}))).expect(1).mount(&server).await;
        let output = generate(
            &server.uri(),
            "key",
            "images",
            "tree",
            &Map::new(),
            &BTreeSet::new(),
        )
        .await
        .unwrap();
        assert_eq!(output.data.len(), 2);
        assert_eq!(output.usage.unwrap()["images"], 2);
    }
    #[test]
    fn image_validation_decodes_and_rejects_truncated_files() {
        let image = image::RgbaImage::new(2, 3);
        let mut encoded = Cursor::new(Vec::new());
        image::DynamicImage::ImageRgba8(image)
            .write_to(&mut encoded, image::ImageFormat::Png)
            .unwrap();
        assert_eq!(
            validate_image(encoded.get_ref()).unwrap(),
            ("image/png".into(), 2, 3)
        );
        assert!(validate_image(&encoded.get_ref()[..25]).is_err());
        assert_eq!(
            content_hash(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
