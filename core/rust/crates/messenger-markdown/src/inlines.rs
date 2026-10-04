//! Prefix-stable inline Markdown parser.
//! (TARGET.md §4, §5)
//!
//! Converts inline text into [`Inline`] spans. Delimiters that are not yet
//! closed during streaming degrade gracefully to [`Inline::Text`].

use messenger_document::Inline;

pub fn parse_inlines(input: &str) -> Vec<Inline> {
    if input.is_empty() {
        return Vec::new();
    }
    let mut inlines = Vec::new();
    let mut i = 0;
    let bytes = input.as_bytes();
    let len = bytes.len();
    let mut text_start = 0;

    while i < len {
        // 1. Inline code: `code`
        if bytes[i] == b'`' {
            if let Some(end) = find_closing_delimiter(bytes, i + 1, b'`') {
                if text_start < i {
                    inlines.push(Inline::Text {
                        text: input[text_start..i].to_string(),
                    });
                }
                inlines.push(Inline::Code {
                    code: input[i + 1..end].to_string(),
                });
                i = end + 1;
                text_start = i;
                continue;
            }
        }

        // 2. Inline math: $formula$ (single $, not followed by another $)
        if bytes[i] == b'$' && (i + 1 >= len || bytes[i + 1] != b'$') {
            if let Some(end) = find_closing_delimiter(bytes, i + 1, b'$') {
                // Ensure not double $$ at end
                if end + 1 >= len || bytes[end + 1] != b'$' {
                    let formula = &input[i + 1..end];
                    if !formula.trim().is_empty() {
                        if text_start < i {
                            inlines.push(Inline::Text {
                                text: input[text_start..i].to_string(),
                            });
                        }
                        inlines.push(Inline::Math {
                            formula: formula.to_string(),
                        });
                        i = end + 1;
                        text_start = i;
                        continue;
                    }
                }
            }
        }

        // 3. Bold: **bold** or __bold__
        if (bytes[i] == b'*' && i + 1 < len && bytes[i + 1] == b'*')
            || (bytes[i] == b'_' && i + 1 < len && bytes[i + 1] == b'_')
        {
            let delim = bytes[i];
            if let Some(end) = find_closing_double(bytes, i + 2, delim) {
                if text_start < i {
                    inlines.push(Inline::Text {
                        text: input[text_start..i].to_string(),
                    });
                }
                inlines.push(Inline::Bold {
                    text: input[i + 2..end].to_string(),
                });
                i = end + 2;
                text_start = i;
                continue;
            }
        }

        // 4. Strikethrough: ~~del~~
        if bytes[i] == b'~' && i + 1 < len && bytes[i + 1] == b'~' {
            if let Some(end) = find_closing_double(bytes, i + 2, b'~') {
                if text_start < i {
                    inlines.push(Inline::Text {
                        text: input[text_start..i].to_string(),
                    });
                }
                inlines.push(Inline::Strikethrough {
                    text: input[i + 2..end].to_string(),
                });
                i = end + 2;
                text_start = i;
                continue;
            }
        }

        // 5. Italic: *italic* or _italic_
        if bytes[i] == b'*' || bytes[i] == b'_' {
            let delim = bytes[i];
            // Skip if this is part of double delimiter ** or __
            if i + 1 < len && bytes[i + 1] == delim {
                i += 1;
                continue;
            }
            if let Some(end) = find_closing_delimiter(bytes, i + 1, delim) {
                if end > i + 1 {
                    if text_start < i {
                        inlines.push(Inline::Text {
                            text: input[text_start..i].to_string(),
                        });
                    }
                    inlines.push(Inline::Italic {
                        text: input[i + 1..end].to_string(),
                    });
                    i = end + 1;
                    text_start = i;
                    continue;
                }
            }
        }

        // 6. Link: [text](url)
        if bytes[i] == b'[' {
            if let Some((link_text, link_url, end_idx)) = try_parse_link(input, i) {
                if text_start < i {
                    inlines.push(Inline::Text {
                        text: input[text_start..i].to_string(),
                    });
                }
                inlines.push(Inline::Link {
                    text: link_text,
                    url: link_url,
                });
                i = end_idx;
                text_start = i;
                continue;
            }
        }

        i += 1;
    }

    if text_start < len {
        inlines.push(Inline::Text {
            text: input[text_start..].to_string(),
        });
    }

    inlines
}

fn find_closing_delimiter(bytes: &[u8], start: usize, delim: u8) -> Option<usize> {
    let mut j = start;
    while j < bytes.len() {
        if bytes[j] == delim && (j == 0 || bytes[j - 1] != b'\\') {
            return Some(j);
        }
        if bytes[j] == b'\n' {
            // Inlines don't span across lines in streaming mode
            return None;
        }
        j += 1;
    }
    None
}

fn find_closing_double(bytes: &[u8], start: usize, delim: u8) -> Option<usize> {
    let mut j = start;
    while j + 1 < bytes.len() {
        if bytes[j] == delim && bytes[j + 1] == delim && (j == 0 || bytes[j - 1] != b'\\') {
            return Some(j);
        }
        if bytes[j] == b'\n' {
            return None;
        }
        j += 1;
    }
    None
}

fn try_parse_link(input: &str, start: usize) -> Option<(String, String, usize)> {
    let rest = &input[start..];
    let closing_bracket = rest.find(']')?;
    if rest[closing_bracket..].starts_with("](") {
        let after_paren = closing_bracket + 2;
        let closing_paren = rest[after_paren..].find(')')? + after_paren;
        let text = rest[1..closing_bracket].to_string();
        let url = rest[after_paren..closing_paren].to_string();
        Some((text, url, start + closing_paren + 1))
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_inline_elements() {
        let spans = parse_inlines("Hello **bold** and *italic* and `code` and $x+y$ and ~~del~~ and [web](https://a.b)");
        assert_eq!(spans.len(), 12);
        assert_eq!(spans[0], Inline::Text { text: "Hello ".into() });
        assert_eq!(spans[1], Inline::Bold { text: "bold".into() });
        assert_eq!(spans[3], Inline::Italic { text: "italic".into() });
        assert_eq!(spans[5], Inline::Code { code: "code".into() });
        assert_eq!(spans[7], Inline::Math { formula: "x+y".into() });
        assert_eq!(spans[9], Inline::Strikethrough { text: "del".into() });
        assert_eq!(spans[11], Inline::Link { text: "web".into(), url: "https://a.b".into() });
    }

    #[test]
    fn unclosed_inline_stays_text() {
        let spans = parse_inlines("Unclosed **bold and `code");
        eprintln!("SPANS: {spans:?}");
        assert_eq!(spans.len(), 1);
        assert_eq!(spans[0], Inline::Text { text: "Unclosed **bold and `code".into() });
    }
}
