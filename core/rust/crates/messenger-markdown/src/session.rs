//! Streaming Document Session with 20–50 ms batching window.
//! (TARGET.md §6)

use messenger_document::{DiffBatch, Document, DocumentDiff};

use crate::parser::IncrementalParser;

pub struct StreamingSession {
    doc: Document,
    parser: IncrementalParser,
    pending_diffs: Vec<DocumentDiff>,
}

impl StreamingSession {
    pub fn new() -> Self {
        Self {
            doc: Document::new(),
            parser: IncrementalParser::new(),
            pending_diffs: Vec::new(),
        }
    }

    pub fn document(&self) -> &Document {
        &self.doc
    }

    /// Feed an incoming token delta and buffer diffs into the current batch.
    pub fn feed(&mut self, text: &str) {
        let diffs = self.parser.feed(text, &mut self.doc);
        self.pending_diffs.extend(diffs);
    }

    /// Drain all accumulated diffs as a batch to be sent over FFI.
    pub fn drain_batch(&mut self) -> DiffBatch {
        DiffBatch {
            diffs: std::mem::take(&mut self.pending_diffs),
        }
    }

    /// Finalize the session when generation finishes.
    pub fn finish(&mut self) -> DiffBatch {
        let diffs = self.parser.flush(&mut self.doc);
        self.pending_diffs.extend(diffs);
        self.drain_batch()
    }
}

impl Default for StreamingSession {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn batching_session_accumulates_and_drains() {
        let mut session = StreamingSession::new();
        session.feed("Hello ");
        session.feed("world!\n\n");

        let batch1 = session.drain_batch();
        assert!(!batch1.is_empty());

        session.feed("```python\nprint(1)\n```\n");
        let batch2 = session.finish();
        assert!(!batch2.is_empty());
        assert_eq!(session.document().blocks().len(), 2);
    }
}
