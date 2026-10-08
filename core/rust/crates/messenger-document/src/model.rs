//! Document AST model with stable Block IDs and immutable/streaming states.
//! (TARGET.md §4.1)

use serde::{Deserialize, Serialize};

pub type BlockId = u64;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BlockStatus {
    Streaming,
    Finalized,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Inline {
    Text { text: String },
    Bold { text: String },
    Italic { text: String },
    Code { code: String },
    Math { formula: String },
    Link { text: String, url: String },
    Strikethrough { text: String },
}

/// One list entry: `indent` counts nesting levels (0 = top level), `ordered`
/// selects the bullet style, `number` is the item's literal ordinal. GFM task
/// list items (`- [ ] ` / `- [x] `) carry `task = Some(checked)`; plain items
/// are `None`. `#[serde(default)]` keeps older payloads (without the field)
/// deserializing.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ListItem {
    pub indent: u8,
    pub ordered: bool,
    pub number: u32,
    #[serde(default)]
    pub task: Option<bool>,
    pub inlines: Vec<Inline>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Block {
    Paragraph {
        id: BlockId,
        inlines: Vec<Inline>,
        status: BlockStatus,
    },
    Heading {
        id: BlockId,
        level: u8,
        text: String,
        status: BlockStatus,
    },
    CodeBlock {
        id: BlockId,
        language: Option<String>,
        code: String,
        status: BlockStatus,
    },
    Math {
        id: BlockId,
        formula: String,
        status: BlockStatus,
    },
    /// Ordered/unordered (possibly nested) list. Each item carries its own
    /// indentation level, marker kind and — for ordered items — literal number
    /// so renderers can rebuild the original markers.
    List {
        id: BlockId,
        items: Vec<ListItem>,
        status: BlockStatus,
    },
    /// Blockquote. Content carries parsed inlines like paragraphs, so nested
    /// bold/italic/code/math markup renders inside the quote instead of
    /// showing literal markers.
    Quote {
        id: BlockId,
        inlines: Vec<Inline>,
        status: BlockStatus,
    },
    ToolCall {
        id: BlockId,
        call_id: String,
        name: String,
        arguments: String,
        output: Option<String>,
        is_error: bool,
        status: BlockStatus,
    },
    Think {
        id: BlockId,
        content: String,
        status: BlockStatus,
    },
    /// Pipe table. Cells carry plain text (inline markup is flattened at
    /// parse time); `head` doubles as the column count anchor.
    Table {
        id: BlockId,
        head: Vec<String>,
        rows: Vec<Vec<String>>,
        status: BlockStatus,
    },
    Divider {
        id: BlockId,
    },
}

impl Block {
    pub fn id(&self) -> BlockId {
        match self {
            Block::Paragraph { id, .. }
            | Block::Heading { id, .. }
            | Block::CodeBlock { id, .. }
            | Block::Math { id, .. }
            | Block::List { id, .. }
            | Block::Quote { id, .. }
            | Block::ToolCall { id, .. }
            | Block::Think { id, .. }
            | Block::Table { id, .. }
            | Block::Divider { id } => *id,
        }
    }

    pub fn status(&self) -> BlockStatus {
        match self {
            Block::Paragraph { status, .. }
            | Block::Heading { status, .. }
            | Block::CodeBlock { status, .. }
            | Block::Math { status, .. }
            | Block::List { status, .. }
            | Block::Quote { status, .. }
            | Block::ToolCall { status, .. }
            | Block::Think { status, .. }
            | Block::Table { status, .. } => *status,
            Block::Divider { .. } => BlockStatus::Finalized,
        }
    }

    pub fn is_finalized(&self) -> bool {
        self.status() == BlockStatus::Finalized
    }

    pub fn finalize(&mut self) {
        match self {
            Block::Paragraph { status, .. }
            | Block::Heading { status, .. }
            | Block::CodeBlock { status, .. }
            | Block::Math { status, .. }
            | Block::List { status, .. }
            | Block::Quote { status, .. }
            | Block::ToolCall { status, .. }
            | Block::Think { status, .. }
            | Block::Table { status, .. } => *status = BlockStatus::Finalized,
            Block::Divider { .. } => {}
        }
    }
}
