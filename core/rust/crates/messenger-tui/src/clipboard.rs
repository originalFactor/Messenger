/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//! Reading the system clipboard for Ctrl-V.
//!
//! A terminal has no clipboard of its own: bracketed paste delivers whatever
//! the terminal emulator chose to send, and an image is never among it. So the
//! image path goes through the platform clipboard directly (arboard covers
//! X11/Wayland/macOS/Windows), and text pasted this way takes the same route
//! as a bracketed paste so both behave identically.
//!
//! ## Why the image is re-encoded rather than passed through
//!
//! Providers accept images as a `data:` URI, so the bytes have to be encoded
//! anyway; doing it here means the size cap is applied BEFORE the base64
//! expansion (a 4 MB screenshot otherwise becomes a 5.3 MB string in the
//! message and in the cloud document), and it normalizes whatever the
//! clipboard handed over — raw RGBA — into one format every provider accepts.
//! PNG rather than JPEG because a screenshot of text must not gain artifacts.

use std::io::Cursor;
use std::path::{Path, PathBuf};

use base64::Engine;
use image::{ImageBuffer, ImageFormat, RgbaImage};

/// The longest edge an attached image is scaled down to, matching the phone
/// client's picker: it is the size vision models are typically trained on, and
/// beyond it the extra pixels cost tokens without adding information.
pub const MAX_IMAGE_EDGE: u32 = 1568;

/// What the clipboard offered.
#[derive(Debug)]
pub enum ClipboardContent {
    Text(String),
    Image(ClipboardImage),
    Empty,
}

/// A pasted image, encoded and ready to become a message part.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClipboardImage {
    pub data_uri: String,
    pub png: Vec<u8>,
    pub width: u32,
    pub height: u32,
}

/// Read the clipboard, preferring an image when it carries one.
///
/// An image wins over any text alongside it (a screenshot tool usually puts
/// both on the clipboard): the user pressing Ctrl-V after a screenshot means
/// the screenshot, whereas pasting the accompanying file path would be a
/// surprise.
pub fn read_clipboard() -> Result<ClipboardContent, String> {
    let mut clipboard = arboard::Clipboard::new().map_err(|error| error.to_string())?;
    if let Ok(image) = clipboard.get_image() {
        if let Some(encoded) = encode_image(&image)? {
            return Ok(ClipboardContent::Image(encoded));
        }
    }
    match clipboard.get_text() {
        Ok(text) if !text.is_empty() => Ok(ClipboardContent::Text(text)),
        Ok(_) => Ok(ClipboardContent::Empty),
        Err(arboard::Error::ContentNotAvailable) => Ok(ClipboardContent::Empty),
        Err(error) => Err(error.to_string()),
    }
}

/// Scale (if needed) and PNG-encode a clipboard image.
///
/// Returns `Ok(None)` for a degenerate image — zero width or height, which
/// some X11 clients produce — so the caller can fall back to text instead of
/// attaching something unrenderable.
pub fn encode_image(image: &arboard::ImageData<'_>) -> Result<Option<ClipboardImage>, String> {
    let (width, height) = (image.width as u32, image.height as u32);
    if width == 0 || height == 0 {
        return Ok(None);
    }
    let buffer: RgbaImage = ImageBuffer::from_raw(width, height, image.bytes.to_vec())
        .ok_or_else(|| "the clipboard image has an unexpected byte length".to_string())?;
    let buffer = downscale(buffer);
    let (width, height) = (buffer.width(), buffer.height());
    let mut png = Vec::new();
    image::DynamicImage::ImageRgba8(buffer)
        .write_to(&mut Cursor::new(&mut png), ImageFormat::Png)
        .map_err(|error| error.to_string())?;
    let data_uri = format!(
        "data:image/png;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(&png)
    );
    Ok(Some(ClipboardImage {
        data_uri,
        png,
        width,
        height,
    }))
}

/// Scale the image so its longest edge fits [`MAX_IMAGE_EDGE`].
///
/// Never upscales: a small image stays the size the user copied it at.
fn downscale(buffer: RgbaImage) -> RgbaImage {
    let (width, height) = (buffer.width(), buffer.height());
    let longest = width.max(height);
    if longest <= MAX_IMAGE_EDGE {
        return buffer;
    }
    let scale = MAX_IMAGE_EDGE as f64 / longest as f64;
    let target_width = ((width as f64 * scale).round() as u32).max(1);
    let target_height = ((height as f64 * scale).round() as u32).max(1);
    image::imageops::resize(
        &buffer,
        target_width,
        target_height,
        image::imageops::FilterType::Lanczos3,
    )
}

/// Write the image's local copy under `directory` and return its path.
///
/// A message part carries BOTH a data URI and a local path
/// (`messenger_llm::domain::MessageImage`), and the local file is what the
/// transcript renders from — so it is written here rather than left to a
/// later step that may not happen.
pub fn write_image(directory: &Path, image: &ClipboardImage) -> Result<String, String> {
    std::fs::create_dir_all(directory).map_err(|error| error.to_string())?;
    let path: PathBuf = directory.join(format!("{}.png", uuid::Uuid::new_v4()));
    std::fs::write(&path, &image.png).map_err(|error| error.to_string())?;
    Ok(path.to_string_lossy().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rgba(width: usize, height: usize) -> Vec<u8> {
        vec![0x40; width * height * 4]
    }

    fn image_of(width: usize, height: usize) -> arboard::ImageData<'static> {
        arboard::ImageData {
            width,
            height,
            bytes: rgba(width, height).into(),
        }
    }

    #[test]
    fn a_small_image_is_encoded_at_its_own_size() {
        let encoded = encode_image(&image_of(64, 32)).unwrap().unwrap();
        assert_eq!((encoded.width, encoded.height), (64, 32));
        assert!(encoded.data_uri.starts_with("data:image/png;base64,"));
        assert!(encoded.png.starts_with(&[0x89, b'P', b'N', b'G']), "a real PNG");
    }

    #[test]
    fn a_large_image_is_scaled_to_the_vision_limit() {
        // 4000x2000 → the long edge is capped at 1568, aspect preserved.
        let encoded = encode_image(&image_of(4_000, 2_000)).unwrap().unwrap();
        assert_eq!(encoded.width, MAX_IMAGE_EDGE);
        assert_eq!(encoded.height, MAX_IMAGE_EDGE / 2);
    }

    #[test]
    fn a_degenerate_image_is_refused_rather_than_attached() {
        assert!(encode_image(&image_of(0, 10)).unwrap().is_none());
        assert!(encode_image(&image_of(10, 0)).unwrap().is_none());
    }

    #[test]
    fn an_image_whose_bytes_do_not_match_its_size_is_an_error() {
        let broken = arboard::ImageData {
            width: 10,
            height: 10,
            bytes: vec![0; 16].into(),
        };
        assert!(encode_image(&broken).is_err());
    }

    #[test]
    fn the_local_copy_lands_under_the_requested_directory() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("chat_images");
        let encoded = encode_image(&image_of(8, 8)).unwrap().unwrap();
        let written = write_image(&target, &encoded).unwrap();
        let path = Path::new(&written);
        assert!(path.starts_with(&target), "{written}");
        assert_eq!(std::fs::read(path).unwrap(), encoded.png);
        assert!(written.ends_with(".png"), "{written}");
    }

    #[test]
    fn each_copy_gets_its_own_file_name() {
        let dir = tempfile::tempdir().unwrap();
        let encoded = encode_image(&image_of(8, 8)).unwrap().unwrap();
        let first = write_image(dir.path(), &encoded).unwrap();
        let second = write_image(dir.path(), &encoded).unwrap();
        assert_ne!(first, second, "two pastes must not overwrite each other");
    }
}
