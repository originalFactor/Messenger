//! Device-local learning records. Chat identifiers are hints, never foreign keys.
use crate::{EntityKind, Store, StoreEvent};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

pub const CREATE_LEARNING_TABLES: &str = r#"
CREATE TABLE IF NOT EXISTS learning_records (
 id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, version INTEGER NOT NULL,
 course_id TEXT, title TEXT NOT NULL, archived INTEGER NOT NULL, updated_at INTEGER NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS learning_kind_course ON learning_records(kind, course_id, updated_at);
CREATE TABLE IF NOT EXISTS learning_revisions (
 item_id TEXT NOT NULL, version INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(item_id, version)
);
CREATE TABLE IF NOT EXISTS learning_review_states (
 card_id TEXT PRIMARY KEY NOT NULL REFERENCES learning_records(id) ON DELETE CASCADE, body TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS learning_review_attempts (
 submission_id TEXT PRIMARY KEY NOT NULL, card_id TEXT NOT NULL,
 created_at INTEGER NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS learning_attempt_card ON learning_review_attempts(card_id, created_at);
"#;

#[derive(Debug)]
pub enum LearningError {
    Storage(rusqlite::Error),
    Invalid(String),
    Conflict(String),
    NotFound,
}
impl From<rusqlite::Error> for LearningError {
    fn from(e: rusqlite::Error) -> Self {
        Self::Storage(e)
    }
}
impl std::fmt::Display for LearningError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Storage(e) => write!(f, "{e}"),
            Self::Invalid(s) | Self::Conflict(s) => f.write_str(s),
            Self::NotFound => f.write_str("learning record not found"),
        }
    }
}
impl std::error::Error for LearningError {}
pub type Result<T> = std::result::Result<T, LearningError>;
fn invalid(s: &str) -> LearningError {
    LearningError::Invalid(s.into())
}
fn encode<T: Serialize>(v: &T) -> Result<String> {
    serde_json::to_string(v).map_err(|e| LearningError::Invalid(e.to_string()))
}
fn decode<T: serde::de::DeserializeOwned>(s: &str) -> Result<T> {
    serde_json::from_str(s).map_err(|e| LearningError::Invalid(e.to_string()))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum LearningKind {
    Course,
    KnowledgePoint,
    Item,
    Material,
    Plan,
    Task,
    ReviewCard,
}
impl LearningKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Course => "course",
            Self::KnowledgePoint => "knowledgePoint",
            Self::Item => "item",
            Self::Material => "material",
            Self::Plan => "plan",
            Self::Task => "task",
            Self::ReviewCard => "reviewCard",
        }
    }
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LearningSource {
    pub kind: SourceKind,
    #[serde(default)]
    pub conversation_id: Option<String>,
    #[serde(default)]
    pub message_ids: Vec<String>,
    pub snapshot: String,
    #[serde(default)]
    pub asset_ids: Vec<String>,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SourceKind {
    Chat,
    Acp,
    Manual,
    Import,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ItemKind {
    Mistake,
    Note,
    Exercise,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum MaterialKind {
    Text,
    Image,
    File,
    Link,
    Artifact,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum TaskStatus {
    Pending,
    InProgress,
    Completed,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub enum LearningContent {
    Course {
        #[serde(default)]
        description: String,
    },
    KnowledgePoint {
        #[serde(default)]
        parent_id: Option<String>,
        #[serde(default)]
        description: String,
        #[serde(default)]
        position: i64,
    },
    Item {
        item_kind: ItemKind,
        #[serde(default)]
        question: String,
        #[serde(default)]
        original_answer: String,
        #[serde(default)]
        correct_answer: String,
        #[serde(default)]
        explanation: String,
        #[serde(default)]
        error_reason: String,
    },
    Material {
        material_kind: MaterialKind,
        #[serde(default)]
        text: String,
        #[serde(default)]
        url: Option<String>,
        #[serde(default)]
        asset_id: Option<String>,
        #[serde(default)]
        artifact_id: Option<String>,
    },
    Plan {
        #[serde(default)]
        goal: String,
        #[serde(default)]
        target_at: Option<i64>,
    },
    Task {
        plan_id: String,
        #[serde(default)]
        item_id: Option<String>,
        status: TaskStatus,
        #[serde(default)]
        position: i64,
        #[serde(default)]
        due_at: Option<i64>,
    },
    ReviewCard {
        item_id: String,
        #[serde(default)]
        paused: bool,
    },
}
impl LearningContent {
    pub fn kind(&self) -> LearningKind {
        match self {
            Self::Course { .. } => LearningKind::Course,
            Self::KnowledgePoint { .. } => LearningKind::KnowledgePoint,
            Self::Item { .. } => LearningKind::Item,
            Self::Material { .. } => LearningKind::Material,
            Self::Plan { .. } => LearningKind::Plan,
            Self::Task { .. } => LearningKind::Task,
            Self::ReviewCard { .. } => LearningKind::ReviewCard,
        }
    }
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LearningRecord {
    pub id: String,
    /// Zero creates; updates must carry the current version. Server increments it.
    #[serde(default)]
    pub version: i64,
    #[serde(default)]
    pub created_at: i64,
    #[serde(default)]
    pub updated_at: i64,
    pub title: String,
    #[serde(default)]
    pub course_id: Option<String>,
    #[serde(default)]
    pub knowledge_point_ids: Vec<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub archived: bool,
    #[serde(default)]
    pub source: Option<LearningSource>,
    pub content: LearningContent,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LearningQuery {
    pub course_id: Option<String>,
    pub knowledge_point_id: Option<String>,
    pub tag: Option<String>,
    pub search: Option<String>,
    pub archived: Option<bool>,
    pub due_before: Option<i64>,
    pub offset: Option<usize>,
    pub limit: Option<usize>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LearningPage {
    pub items: Vec<LearningRecord>,
    pub total: usize,
    pub offset: usize,
    pub limit: usize,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewState {
    pub card_id: String,
    pub version: i64,
    pub interval_days: i64,
    pub repetitions: i64,
    pub ease: f64,
    pub due_at: i64,
    pub last_reviewed_at: Option<i64>,
    pub algorithm: String,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ReviewRating {
    Again,
    Hard,
    Good,
    Easy,
}
impl ReviewRating {
    pub fn quality(self) -> i32 {
        match self {
            Self::Again => 1,
            Self::Hard => 3,
            Self::Good => 4,
            Self::Easy => 5,
        }
    }
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewSubmission {
    pub submission_id: String,
    pub card_id: String,
    pub item_version: i64,
    pub state_version: i64,
    pub answer: String,
    pub rating: ReviewRating,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewAttempt {
    pub submission: ReviewSubmission,
    pub created_at: i64,
    pub item_snapshot: LearningRecord,
    pub state: ReviewState,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LearningOverview {
    pub courses: usize,
    pub items: usize,
    pub materials: usize,
    pub due_reviews: usize,
    pub pending_tasks: usize,
    pub completed_tasks: usize,
    pub attempts: usize,
    pub local_only: bool,
}

fn load(conn: &Connection, id: &str) -> Result<Option<LearningRecord>> {
    let body: Option<String> = conn
        .query_row("SELECT body FROM learning_records WHERE id=?1", [id], |r| {
            r.get(0)
        })
        .optional()?;
    let mut record: Option<LearningRecord> = body.map(|s| decode(&s)).transpose()?;
    if let Some(r) = &mut record {
        detach_source(conn, r)?;
    }
    Ok(record)
}
fn detach_source(conn: &Connection, r: &mut LearningRecord) -> Result<()> {
    if let Some(source) = &mut r.source {
        if source.kind == SourceKind::Chat {
            if let Some(id) = &source.conversation_id {
                let exists: bool = conn.query_row(
                    "SELECT EXISTS(SELECT 1 FROM conversations WHERE id=?1)",
                    [id],
                    |row| row.get(0),
                )?;
                if !exists {
                    source.conversation_id = None;
                    source.message_ids.clear();
                    return Ok(());
                }
            }
            let mut retained = Vec::new();
            for id in &source.message_ids {
                let exists: bool = conn.query_row(
                    "SELECT EXISTS(SELECT 1 FROM messages WHERE id=?1 AND conversationId=?2)",
                    params![id, source.conversation_id],
                    |row| row.get(0),
                )?;
                if exists {
                    retained.push(id.clone());
                }
            }
            source.message_ids = retained;
        }
    }
    Ok(())
}
fn require(conn: &Connection, id: &str, kind: LearningKind) -> Result<LearningRecord> {
    let record = load(conn, id)?.ok_or(LearningError::NotFound)?;
    if record.content.kind() != kind {
        return Err(invalid("reference has incorrect learning kind"));
    }
    Ok(record)
}
fn validate(conn: &Connection, r: &LearningRecord) -> Result<()> {
    if r.id.trim().is_empty()
        || r.id.len() > 128
        || r.id.chars().any(char::is_control)
        || r.title.trim().is_empty()
        || r.title.len() > 1024
        || r.version < 0
        || r.version == i64::MAX
    {
        return Err(invalid("invalid id, title or version"));
    }
    if encode(r)?.len() > 1024 * 1024
        || r.tags.len() > 64
        || r.tags.iter().any(|t| t.trim().is_empty() || t.len() > 128)
        || r.knowledge_point_ids.len() > 128
    {
        return Err(invalid("learning record exceeds limits"));
    }
    if let Some(id) = &r.course_id {
        require(conn, id, LearningKind::Course)?;
    }
    for id in &r.knowledge_point_ids {
        let point = require(conn, id, LearningKind::KnowledgePoint)?;
        if point.course_id != r.course_id {
            return Err(invalid("knowledge point belongs to another course"));
        }
    }
    match &r.content {
        LearningContent::Course { .. } => {
            if r.course_id.is_some() || !r.knowledge_point_ids.is_empty() {
                return Err(invalid(
                    "course cannot have a parent course or knowledge points",
                ));
            }
        }
        LearningContent::KnowledgePoint { parent_id, .. } => {
            if r.course_id.is_none() {
                return Err(invalid("knowledge point requires courseId"));
            }
            let mut next = parent_id.clone();
            let mut seen = std::collections::HashSet::new();
            while let Some(id) = next {
                if id == r.id || !seen.insert(id.clone()) {
                    return Err(invalid("knowledge point cycle"));
                }
                let parent = require(conn, &id, LearningKind::KnowledgePoint)?;
                if parent.course_id != r.course_id {
                    return Err(invalid("parent belongs to another course"));
                }
                next = match parent.content {
                    LearningContent::KnowledgePoint { parent_id, .. } => parent_id,
                    _ => None,
                };
            }
        }
        LearningContent::Task {
            plan_id, item_id, ..
        } => {
            let plan = require(conn, plan_id, LearningKind::Plan)?;
            if plan.course_id != r.course_id {
                return Err(invalid("task belongs to another course than plan"));
            }
            if let Some(id) = item_id {
                require(conn, id, LearningKind::Item)?;
            }
        }
        LearningContent::ReviewCard { item_id, .. } => {
            let item = require(conn, item_id, LearningKind::Item)?;
            if item.course_id != r.course_id {
                return Err(invalid("card belongs to another course than item"));
            }
        }
        LearningContent::Material {
            material_kind,
            url,
            asset_id,
            artifact_id,
            ..
        } => match material_kind {
            MaterialKind::Link
                if !url
                    .as_deref()
                    .is_some_and(|u| u.starts_with("https://") || u.starts_with("http://")) =>
            {
                return Err(invalid("link material requires an HTTP(S) URL"))
            }
            MaterialKind::Image | MaterialKind::File
                if !asset_id.as_deref().is_some_and(|s| !s.trim().is_empty()) =>
            {
                return Err(invalid(
                    "file/image material requires an independently owned assetId",
                ))
            }
            MaterialKind::Artifact
                if !artifact_id.as_deref().is_some_and(|s| !s.trim().is_empty()) =>
            {
                return Err(invalid("artifact material requires artifactId"))
            }
            _ => {}
        },
        _ => {}
    }
    Ok(())
}
fn save(conn: &Connection, r: &LearningRecord) -> Result<()> {
    let body = encode(r)?;
    conn.execute("INSERT INTO learning_records(id,kind,version,course_id,title,archived,updated_at,body) VALUES(?1,?2,?3,?4,?5,?6,?7,?8) ON CONFLICT(id) DO UPDATE SET version=excluded.version,course_id=excluded.course_id,title=excluded.title,archived=excluded.archived,updated_at=excluded.updated_at,body=excluded.body", params![r.id,r.content.kind().as_str(),r.version,r.course_id,r.title,r.archived,r.updated_at,body])?;
    conn.execute(
        "INSERT INTO learning_revisions(item_id,version,body) VALUES(?1,?2,?3)",
        params![r.id, r.version, body],
    )?;
    Ok(())
}
impl Store {
    fn learning_event(&self, id: &str) {
        self.notify(StoreEvent {
            kind: EntityKind::Other,
            ids: vec![format!("learning:{id}")],
        });
    }
    pub fn get_learning(&self, id: &str) -> Result<Option<LearningRecord>> {
        load(&self.conn.lock().unwrap(), id)
    }
    pub fn upsert_learning(&self, r: LearningRecord, now: i64) -> Result<LearningRecord> {
        self.upsert_learning_with_local_kv(r, now, &[])
    }
    /// Caller validates owned resource bytes under the asset/domain mutation gates.
    /// This commits ownership metadata with the learning content revision.
    pub fn upsert_learning_with_local_kv(
        &self,
        mut r: LearningRecord,
        now: i64,
        entries: &[(String, String)],
    ) -> Result<LearningRecord> {
        if entries.len() > 256
            || entries.iter().any(|(k, v)| {
                !k.starts_with("local_asset:") || k.len() > 512 || v.len() > 4 * 1024 * 1024
            })
        {
            return Err(invalid("invalid learning asset ownership metadata"));
        }
        {
            let mut conn = self.conn.lock().unwrap();
            let tx = conn.transaction()?;
            validate(&tx, &r)?;
            let old = load(&tx, &r.id)?;
            if old.is_none()
                && tx.query_row(
                    "SELECT EXISTS(SELECT 1 FROM learning_revisions WHERE item_id=?1)",
                    [&r.id],
                    |row| row.get::<_, bool>(0),
                )?
            {
                return Err(LearningError::Conflict(
                    "record ID was already used; choose a new ID".into(),
                ));
            }
            if old.as_ref().map_or(0, |v| v.version) != r.version {
                return Err(LearningError::Conflict(
                    "learning record version changed".into(),
                ));
            }
            if let Some(old) = &old {
                if old.content.kind() != r.content.kind() {
                    return Err(invalid("record kind cannot change"));
                }
                if let (
                    LearningContent::ReviewCard { item_id: a, .. },
                    LearningContent::ReviewCard { item_id: b, .. },
                ) = (&old.content, &r.content)
                {
                    if a != b {
                        return Err(invalid("review card item cannot change"));
                    }
                }
                // Reparenting a referenced course/point/item would invalidate other records.
                if old.course_id != r.course_id && has_dependents(&tx, &r.id)? {
                    return Err(LearningError::Conflict(
                        "record is referenced; update dependents first".into(),
                    ));
                }
            }
            r.created_at = old.as_ref().map_or(now, |v| v.created_at);
            r.updated_at = now;
            r.version += 1;
            // A normal-chat source can become dangling during import/restore. Keep its snapshot.
            if let Some(source) = &mut r.source {
                if source.kind == SourceKind::Chat {
                    if let Some(id) = &source.conversation_id {
                        let exists: bool = tx.query_row(
                            "SELECT EXISTS(SELECT 1 FROM conversations WHERE id=?1)",
                            [id],
                            |row| row.get(0),
                        )?;
                        if !exists {
                            source.conversation_id = None;
                            source.message_ids.clear();
                        }
                    }
                }
            }
            save(&tx, &r)?;
            if old.is_none() && r.content.kind() == LearningKind::ReviewCard {
                let s = ReviewState {
                    card_id: r.id.clone(),
                    version: 0,
                    interval_days: 0,
                    repetitions: 0,
                    ease: 2.5,
                    due_at: now,
                    last_reviewed_at: None,
                    algorithm: "sm2-v1".into(),
                };
                tx.execute(
                    "INSERT INTO learning_review_states(card_id,body) VALUES(?1,?2)",
                    params![r.id, encode(&s)?],
                )?;
            }
            for (key, value) in entries {
                tx.execute("INSERT INTO kv(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",params![key,value])?;
            }
            tx.commit()?;
        }
        self.learning_event(&r.id);
        if !entries.is_empty() {
            self.notify(StoreEvent {
                kind: EntityKind::Other,
                ids: entries.iter().map(|(k, _)| k.clone()).collect(),
            });
        }
        Ok(r)
    }
    pub fn list_learning(&self, kind: LearningKind, q: &LearningQuery) -> Result<LearningPage> {
        let conn = self.conn.lock().unwrap();
        let filter = " FROM learning_records r LEFT JOIN learning_review_states s ON s.card_id=r.id
            WHERE r.kind=?1 AND (?2 IS NULL OR r.course_id=?2)
            AND (?3 IS NULL OR r.archived=?3)
            AND (?4 IS NULL OR EXISTS(SELECT 1 FROM json_each(r.body,'$.knowledgePointIds') WHERE value=?4))
            AND (?5 IS NULL OR EXISTS(SELECT 1 FROM json_each(r.body,'$.tags') WHERE value=?5))
            AND (?6 IS NULL OR instr(lower(r.body),lower(?6))>0)
            AND (?7 IS NULL OR (r.archived=0 AND r.kind='reviewCard'
                AND json_extract(r.body,'$.content.paused')=0 AND json_extract(s.body,'$.dueAt')<=?7))";
        let total = conn.query_row(
            &format!("SELECT COUNT(*){filter}"),
            params![
                kind.as_str(),
                q.course_id,
                q.archived,
                q.knowledge_point_id,
                q.tag,
                q.search,
                q.due_before
            ],
            |r| r.get::<_, i64>(0),
        )? as usize;
        let offset = q.offset.unwrap_or(0).min(i64::MAX as usize);
        let limit = q.limit.unwrap_or(50).clamp(1, 200);
        let order = if q.due_before.is_some() {
            "json_extract(s.body,'$.dueAt'),r.id"
        } else if matches!(kind, LearningKind::Task | LearningKind::KnowledgePoint) {
            "json_extract(r.body,'$.content.position'),r.id"
        } else {
            "r.updated_at DESC,r.id"
        };
        let mut stmt = conn.prepare(&format!(
            "SELECT r.body{filter} ORDER BY {order} LIMIT ?8 OFFSET ?9"
        ))?;
        let bodies = stmt
            .query_map(
                params![
                    kind.as_str(),
                    q.course_id,
                    q.archived,
                    q.knowledge_point_id,
                    q.tag,
                    q.search,
                    q.due_before,
                    limit as i64,
                    offset as i64
                ],
                |r| r.get::<_, String>(0),
            )?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        let mut items = Vec::with_capacity(bodies.len());
        for body in bodies {
            let mut r = decode(&body)?;
            detach_source(&conn, &mut r)?;
            items.push(r);
        }
        Ok(LearningPage {
            items,
            total,
            offset,
            limit,
        })
    }
    pub fn delete_learning(&self, id: &str, version: i64) -> Result<()> {
        self.delete_learning_with_local_kv(id, version, &[])
    }
    /// Delete the record/revisions and release asset owners in the same transaction.
    /// Caller holds the domain and asset mutation gates and preserves review-history owners.
    pub fn delete_learning_with_local_kv(
        &self,
        id: &str,
        version: i64,
        entries: &[(String, String)],
    ) -> Result<()> {
        if entries.len() > 10000
            || entries.iter().any(|(k, v)| {
                !k.starts_with("local_asset:") || k.len() > 512 || v.len() > 8 * 1024 * 1024
            })
            || entries
                .iter()
                .map(|(k, v)| k.len() + v.len())
                .sum::<usize>()
                > 96 * 1024 * 1024
        {
            return Err(invalid("invalid learning asset release metadata"));
        }
        {
            let mut conn = self.conn.lock().unwrap();
            let tx = conn.transaction()?;
            let r = load(&tx, id)?.ok_or(LearningError::NotFound)?;
            if r.version != version {
                return Err(LearningError::Conflict(
                    "learning record version changed".into(),
                ));
            }
            if has_dependents(&tx, id)? {
                return Err(LearningError::Conflict(
                    "record is referenced; archive it or remove dependents first".into(),
                ));
            }
            tx.execute("DELETE FROM learning_revisions WHERE item_id=?1", [id])?;
            tx.execute("DELETE FROM learning_records WHERE id=?1", [id])?;
            for (key, value) in entries {
                tx.execute("INSERT INTO kv(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",params![key,value])?;
            }
            tx.commit()?;
        }
        self.learning_event(id);
        if !entries.is_empty() {
            self.notify(StoreEvent {
                kind: EntityKind::Other,
                ids: entries.iter().map(|(k, _)| k.clone()).collect(),
            });
        }
        Ok(())
    }
    /// Immutable review snapshots retain their source assets after item deletion.
    pub fn learning_attempt_asset_ids(&self, item_id: &str) -> Result<Vec<String>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt=conn.prepare("SELECT DISTINCT assets.value FROM learning_review_attempts a, json_each(a.body,'$.itemSnapshot.source.assetIds') assets WHERE json_extract(a.body,'$.itemSnapshot.id')=?1")?;
        let ids = stmt
            .query_map([item_id], |row| row.get::<_, String>(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(ids)
    }
    pub fn learning_review_state(&self, id: &str) -> Result<Option<ReviewState>> {
        review_state(&self.conn.lock().unwrap(), id)
    }
    pub fn learning_attempts(
        &self,
        card_id: &str,
        offset: usize,
        limit: usize,
    ) -> Result<Vec<ReviewAttempt>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt=conn.prepare("SELECT body FROM learning_review_attempts WHERE card_id=?1 ORDER BY created_at DESC,submission_id LIMIT ?2 OFFSET ?3")?;
        let bodies = stmt
            .query_map(
                params![
                    card_id,
                    limit.clamp(1, 200) as i64,
                    offset.min(i64::MAX as usize) as i64
                ],
                |r| r.get::<_, String>(0),
            )?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        bodies.iter().map(|s| decode(s)).collect()
    }
    /// The scheduler runs while the same transaction checks idempotence and versions.
    pub fn submit_learning_review(
        &self,
        submission: ReviewSubmission,
        now: i64,
        schedule: fn(&ReviewState, ReviewRating, i64) -> ReviewState,
    ) -> Result<ReviewAttempt> {
        let result;
        {
            let mut conn = self.conn.lock().unwrap();
            let tx = conn.transaction()?;
            if submission.submission_id.trim().is_empty()
                || submission.submission_id.len() > 128
                || submission.answer.len() > 1024 * 1024
            {
                return Err(invalid("invalid review submission"));
            }
            let existing: Option<String> = tx
                .query_row(
                    "SELECT body FROM learning_review_attempts WHERE submission_id=?1",
                    [&submission.submission_id],
                    |r| r.get(0),
                )
                .optional()?;
            if let Some(body) = existing {
                let attempt: ReviewAttempt = decode(&body)?;
                if attempt.submission != submission {
                    return Err(LearningError::Conflict(
                        "submission ID already used for different input".into(),
                    ));
                }
                return Ok(attempt);
            }
            let card = require(&tx, &submission.card_id, LearningKind::ReviewCard)?;
            let item_id = match &card.content {
                LearningContent::ReviewCard {
                    item_id,
                    paused: false,
                } if !card.archived => item_id,
                _ => {
                    return Err(LearningError::Conflict(
                        "review card is paused or archived".into(),
                    ))
                }
            };
            let item = require(&tx, item_id, LearningKind::Item)?;
            let old = review_state(&tx, &card.id)?.ok_or(LearningError::NotFound)?;
            if item.version != submission.item_version || old.version != submission.state_version {
                return Err(LearningError::Conflict(
                    "item or review state version changed".into(),
                ));
            }
            let state = schedule(&old, submission.rating, now);
            validate_state(&state)?;
            if state.card_id != old.card_id
                || state.version
                    != old
                        .version
                        .checked_add(1)
                        .ok_or_else(|| invalid("review version overflow"))?
            {
                return Err(invalid("invalid scheduler transition"));
            }
            result = ReviewAttempt {
                submission,
                created_at: now,
                item_snapshot: item,
                state: state.clone(),
            };
            tx.execute(
                "UPDATE learning_review_states SET body=?2 WHERE card_id=?1",
                params![card.id, encode(&state)?],
            )?;
            tx.execute("INSERT INTO learning_review_attempts(submission_id,card_id,created_at,body) VALUES(?1,?2,?3,?4)",params![result.submission.submission_id,card.id,now,encode(&result)?])?;
            tx.commit()?;
        }
        self.learning_event(&result.submission.card_id);
        Ok(result)
    }
    pub fn learning_overview(&self, now: i64) -> Result<LearningOverview> {
        let all = |kind| {
            self.list_learning(
                kind,
                &LearningQuery {
                    archived: Some(false),
                    limit: Some(200),
                    ..Default::default()
                },
            )
        };
        let conn = self.conn.lock().unwrap();
        let pending_tasks:usize=conn.query_row("SELECT COUNT(*) FROM learning_records WHERE kind='task' AND archived=0 AND json_extract(body,'$.content.status')!='completed'",[],|r|r.get::<_,i64>(0).map(|n|n as usize))?;
        let completed_tasks:usize=conn.query_row("SELECT COUNT(*) FROM learning_records WHERE kind='task' AND archived=0 AND json_extract(body,'$.content.status')='completed'",[],|r|r.get::<_,i64>(0).map(|n|n as usize))?;
        let attempts: usize =
            conn.query_row("SELECT COUNT(*) FROM learning_review_attempts", [], |r| {
                r.get::<_, i64>(0).map(|n| n as usize)
            })?;
        drop(conn);
        Ok(LearningOverview {
            courses: all(LearningKind::Course)?.total,
            items: all(LearningKind::Item)?.total,
            materials: all(LearningKind::Material)?.total,
            due_reviews: self
                .list_learning(
                    LearningKind::ReviewCard,
                    &LearningQuery {
                        due_before: Some(now),
                        ..Default::default()
                    },
                )?
                .total,
            pending_tasks,
            completed_tasks,
            attempts,
            local_only: true,
        })
    }
    pub fn clear_learning(&self) -> Result<()> {
        {
            let mut conn = self.conn.lock().unwrap();
            let tx = conn.transaction()?;
            tx.execute_batch("DELETE FROM learning_review_attempts; DELETE FROM learning_review_states; DELETE FROM learning_revisions; DELETE FROM learning_records;")?;
            tx.commit()?;
        }
        self.learning_event("*");
        Ok(())
    }
}
fn review_state(conn: &Connection, id: &str) -> Result<Option<ReviewState>> {
    let body: Option<String> = conn
        .query_row(
            "SELECT body FROM learning_review_states WHERE card_id=?1",
            [id],
            |r| r.get(0),
        )
        .optional()?;
    body.map(|s| decode(&s)).transpose()
}
/// Portable learning metadata. Binary assets and executable artifacts are explicitly external.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LearningPackage {
    pub format_version: u32,
    pub records: Vec<LearningRecord>,
    pub revisions: Vec<LearningRecord>,
    pub review_states: Vec<ReviewState>,
    pub attempts: Vec<ReviewAttempt>,
    pub external_resources_included: bool,
}
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LearningImportPreview {
    pub records: usize,
    pub revisions: usize,
    pub attempts: usize,
    pub external_resources_included: bool,
    pub warnings: Vec<String>,
}
impl Store {
    pub fn export_learning(&self) -> Result<LearningPackage> {
        let conn = self.conn.lock().unwrap();
        fn rows<T: serde::de::DeserializeOwned>(conn: &Connection, sql: &str) -> Result<Vec<T>> {
            let mut stmt = conn.prepare(sql)?;
            let bodies = stmt
                .query_map([], |r| r.get::<_, String>(0))?
                .collect::<std::result::Result<Vec<_>, _>>()?;
            bodies.iter().map(|s| decode(s)).collect()
        }
        Ok(LearningPackage {
            format_version: 1,
            records: rows(&conn, "SELECT body FROM learning_records ORDER BY id")?,
            revisions: rows(
                &conn,
                "SELECT body FROM learning_revisions ORDER BY item_id,version",
            )?,
            review_states: rows(
                &conn,
                "SELECT body FROM learning_review_states ORDER BY card_id",
            )?,
            attempts: rows(
                &conn,
                "SELECT body FROM learning_review_attempts ORDER BY created_at,submission_id",
            )?,
            external_resources_included: false,
        })
    }
    /// Preview performs exactly the same validation and rolls back its temporary writes.
    pub fn import_learning(
        &self,
        package: &LearningPackage,
        commit: bool,
    ) -> Result<LearningImportPreview> {
        self.import_learning_with_local_kv(package, commit, &[])
    }
    /// Resource bundle adapter: learning rows and local resource metadata commit together.
    /// Caller stages resource bytes and holds its resource mutation gate first.
    pub fn import_learning_with_local_kv(
        &self,
        package: &LearningPackage,
        commit: bool,
        entries: &[(String, String)],
    ) -> Result<LearningImportPreview> {
        // 10000 assets + 10000 optional provenance entries + 1000 artifacts.
        if entries.len() > 21000
            || entries.iter().any(|(k, v)| {
                !(k.starts_with("local_asset:")
                    || k.starts_with("artifact:")
                    || k.starts_with("provenance:"))
                    || k.len() > 512
                    || v.len() > 96 * 1024 * 1024
            })
            || entries
                .iter()
                .map(|(k, v)| k.len() + v.len())
                .sum::<usize>()
                > 96 * 1024 * 1024
        {
            return Err(invalid("invalid local resource metadata namespace or size"));
        }
        if package.format_version != 1 || package.external_resources_included {
            return Err(invalid(
                "unsupported learning package format; assets/artifacts must be imported separately",
            ));
        }
        if package.review_states.len() > 10000
            || package.records.len() > 10000
            || package.revisions.len() > 50000
            || package.attempts.len() > 50000
            || encode(package)?.len() > 16 * 1024 * 1024
        {
            return Err(invalid("learning package exceeds limits"));
        }
        let preview=LearningImportPreview{records:package.records.len(),revisions:package.revisions.len(),attempts:package.attempts.len(),external_resources_included:false,warnings:vec!["Metadata only: asset and artifact references require their original local resources or a separate resource import. No network or execution permissions are imported.".into()]};
        {
            let mut conn = self.conn.lock().unwrap();
            let tx = conn.transaction()?;
            let mut ids = std::collections::HashSet::new();
            for r in &package.records {
                if !ids.insert(r.id.clone()) || load(&tx, &r.id)?.is_some() {
                    return Err(LearningError::Conflict(
                        "import IDs already exist; import into a library without these IDs".into(),
                    ));
                }
                if r.version < 1 || r.version == i64::MAX {
                    return Err(invalid("invalid imported version"));
                }
                // Insert all records before relation validation so package order is irrelevant.
                tx.execute("INSERT INTO learning_records(id,kind,version,course_id,title,archived,updated_at,body) VALUES(?1,?2,?3,?4,?5,?6,?7,?8)",params![r.id,r.content.kind().as_str(),r.version,r.course_id,r.title,r.archived,r.updated_at,encode(r)?])?;
            }
            for r in &package.records {
                validate(&tx, r)?;
            }
            let mut revisions = std::collections::HashSet::new();
            for r in &package.revisions {
                if r.version < 1 || !revisions.insert((r.id.clone(), r.version)) {
                    return Err(invalid("invalid or duplicate revision"));
                }
                if let Some(current) = package.records.iter().find(|c| c.id == r.id) {
                    if r.version > current.version
                        || r.content.kind() != current.content.kind()
                        || (r.version == current.version && r != current)
                    {
                        return Err(invalid("invalid revision kind/version or current snapshot"));
                    }
                }
                let exists:bool=tx.query_row("SELECT EXISTS(SELECT 1 FROM learning_revisions WHERE item_id=?1 AND version=?2)",params![r.id,r.version],|row|row.get(0))?;
                if exists {
                    return Err(LearningError::Conflict(
                        "learning revision already exists".into(),
                    ));
                }
                tx.execute(
                    "INSERT INTO learning_revisions(item_id,version,body) VALUES(?1,?2,?3)",
                    params![r.id, r.version, encode(r)?],
                )?;
            }
            for r in &package.records {
                if !revisions.contains(&(r.id.clone(), r.version)) {
                    return Err(invalid("current record revision missing"));
                }
            }
            let mut states = std::collections::HashSet::new();
            for s in &package.review_states {
                if !ids.contains(&s.card_id) || !states.insert(s.card_id.clone()) {
                    return Err(invalid(
                        "review state must belong to an imported card exactly once",
                    ));
                }
                require(&tx, &s.card_id, LearningKind::ReviewCard)?;
                validate_state(s)?;
                tx.execute(
                    "INSERT INTO learning_review_states(card_id,body) VALUES(?1,?2)",
                    params![s.card_id, encode(s)?],
                )?;
            }
            for r in &package.records {
                if r.content.kind() == LearningKind::ReviewCard && !states.contains(&r.id) {
                    return Err(invalid("review state missing"));
                }
            }
            let mut submissions = std::collections::HashSet::new();
            for a in &package.attempts {
                if !submissions.insert(a.submission.submission_id.clone())
                    || a.submission.submission_id.trim().is_empty()
                    || a.submission.submission_id.len() > 128
                    || a.item_snapshot.content.kind() != LearningKind::Item
                    || a.item_snapshot.version != a.submission.item_version
                    || a.state.card_id != a.submission.card_id
                {
                    return Err(invalid("invalid imported attempt"));
                }
                validate_state(&a.state)?;
                let existing: bool = tx.query_row(
                    "SELECT EXISTS(SELECT 1 FROM learning_review_attempts WHERE submission_id=?1)",
                    [&a.submission.submission_id],
                    |r| r.get(0),
                )?;
                if existing {
                    return Err(LearningError::Conflict(
                        "review submission ID already exists".into(),
                    ));
                }
                tx.execute("INSERT INTO learning_review_attempts(submission_id,card_id,created_at,body) VALUES(?1,?2,?3,?4)",params![a.submission.submission_id,a.submission.card_id,a.created_at,encode(a)?])?;
            }
            let mut resource_keys = std::collections::HashSet::new();
            for (key, value) in entries {
                if !resource_keys.insert(key) {
                    return Err(invalid("duplicate resource metadata key"));
                }
                if key.starts_with("artifact:") {
                    let exists: bool =
                        tx.query_row("SELECT EXISTS(SELECT 1 FROM kv WHERE key=?1)", [key], |r| {
                            r.get(0)
                        })?;
                    if exists {
                        return Err(LearningError::Conflict("artifact ID already exists".into()));
                    }
                }
                tx.execute("INSERT INTO kv(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",params![key,value])?;
            }
            if commit {
                tx.commit()?;
            }
        }
        if commit {
            self.learning_event("*");
            if !entries.is_empty() {
                self.notify(StoreEvent {
                    kind: EntityKind::Other,
                    ids: entries.iter().map(|(k, _)| k.clone()).collect(),
                });
            }
        }
        Ok(preview)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn record(id: &str, content: serde_json::Value) -> LearningRecord {
        serde_json::from_value(json!({"id":id,"title":id,"content":content})).unwrap()
    }
    fn fixture(store: &Store) -> (LearningRecord, LearningRecord) {
        let item=store.upsert_learning(record("item",json!({"kind":"item","itemKind":"mistake","question":"2+2?","correctAnswer":"4"})),10).unwrap();
        let card = store
            .upsert_learning(
                record("card", json!({"kind":"reviewCard","itemId":"item"})),
                10,
            )
            .unwrap();
        (item, card)
    }
    fn scheduler(old: &ReviewState, _: ReviewRating, now: i64) -> ReviewState {
        let mut s = old.clone();
        s.version += 1;
        s.due_at = now + 86_400_000;
        s.last_reviewed_at = Some(now);
        s.interval_days = 1;
        s.repetitions += 1;
        s
    }
    fn submission() -> ReviewSubmission {
        ReviewSubmission {
            submission_id: "submit".into(),
            card_id: "card".into(),
            item_version: 1,
            state_version: 0,
            answer: "4".into(),
            rating: ReviewRating::Good,
        }
    }
    #[test]
    fn outlines_and_tasks_follow_position_before_pagination() {
        let store = Store::open_memory().unwrap();
        store
            .upsert_learning(record("course", json!({"kind":"course"})), 0)
            .unwrap();
        store
            .upsert_learning(record("plan", json!({"kind":"plan"})), 0)
            .unwrap();
        for (index, (id, position)) in [("later", 20), ("first-b", 10), ("first-a", 10)]
            .into_iter()
            .enumerate()
        {
            let mut point = record(
                &format!("point-{id}"),
                json!({"kind":"knowledgePoint","position":position}),
            );
            point.course_id = Some("course".into());
            store.upsert_learning(point, index as i64).unwrap();
            store.upsert_learning(record(&format!("task-{id}"),json!({"kind":"task","planId":"plan","status":"pending","position":position})),index as i64).unwrap();
        }
        for (kind, prefix) in [
            (LearningKind::Task, "task"),
            (LearningKind::KnowledgePoint, "point"),
        ] {
            let page = store
                .list_learning(
                    kind,
                    &LearningQuery {
                        offset: Some(1),
                        limit: Some(1),
                        ..Default::default()
                    },
                )
                .unwrap();
            assert_eq!(page.total, 3);
            assert_eq!(page.items[0].id, format!("{prefix}-first-b"));
            assert_eq!(
                store
                    .list_learning(
                        kind,
                        &LearningQuery {
                            offset: Some(2),
                            limit: Some(1),
                            ..Default::default()
                        }
                    )
                    .unwrap()
                    .items[0]
                    .id,
                format!("{prefix}-later")
            );
        }
    }
    #[test]
    fn versions_relations_filters_and_cycle_validation() {
        let store = Store::open_memory().unwrap();
        let course = store
            .upsert_learning(record("course", json!({"kind":"course"})), 10)
            .unwrap();
        let mut p = record("point", json!({"kind":"knowledgePoint"}));
        p.course_id = Some(course.id.clone());
        p.tags = vec!["math".into()];
        let point = store.upsert_learning(p.clone(), 11).unwrap();
        assert_eq!(point.version, 1);
        assert!(matches!(
            store.upsert_learning(p, 12),
            Err(LearningError::Conflict(_))
        ));
        let mut cycle = point.clone();
        cycle.content = LearningContent::KnowledgePoint {
            parent_id: Some(point.id.clone()),
            description: String::new(),
            position: 0,
        };
        assert!(store.upsert_learning(cycle, 12).is_err());
        let q = LearningQuery {
            course_id: Some(course.id.clone()),
            tag: Some("math".into()),
            search: Some("POINT".into()),
            limit: Some(1),
            ..Default::default()
        };
        assert_eq!(
            store
                .list_learning(LearningKind::KnowledgePoint, &q)
                .unwrap()
                .total,
            1
        );
        assert!(matches!(
            store.delete_learning(&course.id, course.version),
            Err(LearningError::Conflict(_))
        ));
        store.delete_learning(&point.id, point.version).unwrap();
        store.delete_learning(&course.id, course.version).unwrap();
    }
    #[test]
    fn immutable_attempt_snapshot_idempotence_pause_and_failed_transaction() {
        let store = Store::open_memory().unwrap();
        let (mut item, mut card) = fixture(&store);
        let attempt = store
            .submit_learning_review(submission(), 20, scheduler)
            .unwrap();
        let duplicate = store
            .submit_learning_review(submission(), 2000, scheduler)
            .unwrap();
        assert_eq!(duplicate, attempt);
        assert_eq!(
            store
                .learning_review_state("card")
                .unwrap()
                .unwrap()
                .version,
            1
        );
        let mut collision = submission();
        collision.answer = "5".into();
        assert!(matches!(
            store.submit_learning_review(collision, 30, scheduler),
            Err(LearningError::Conflict(_))
        ));
        item.title = "Edited after review".into();
        store.upsert_learning(item, 30).unwrap();
        assert_eq!(
            store.learning_attempts("card", 0, 20).unwrap()[0]
                .item_snapshot
                .title,
            "item"
        );
        card.content = LearningContent::ReviewCard {
            item_id: "item".into(),
            paused: true,
        };
        store.upsert_learning(card, 30).unwrap();
        let mut fresh = submission();
        fresh.submission_id = "new".into();
        fresh.item_version = 2;
        fresh.state_version = 1;
        assert!(store
            .submit_learning_review(fresh.clone(), 40, scheduler)
            .is_err());
        assert_eq!(store.learning_attempts("card", 0, 20).unwrap().len(), 1);
        assert_eq!(
            store
                .list_learning(
                    LearningKind::ReviewCard,
                    &LearningQuery {
                        due_before: Some(i64::MAX),
                        ..Default::default()
                    }
                )
                .unwrap()
                .total,
            0
        );
        // An invalid scheduler transition rolls back both the attempt and state.
        let mut card = store.get_learning("card").unwrap().unwrap();
        card.content = LearningContent::ReviewCard {
            item_id: "item".into(),
            paused: false,
        };
        store.upsert_learning(card, 40).unwrap();
        fn broken(s: &ReviewState, _: ReviewRating, _: i64) -> ReviewState {
            let mut s = s.clone();
            s.ease = 0.1;
            s.version += 1;
            s
        }
        assert!(store.submit_learning_review(fresh, 50, broken).is_err());
        assert_eq!(
            store
                .learning_review_state("card")
                .unwrap()
                .unwrap()
                .version,
            1
        );
    }
    #[test]
    fn snapshots_survive_source_deletion_and_full_chat_replacement() {
        let store = Store::open_memory().unwrap();
        fixture(&store);
        let mut item = store.get_learning("item").unwrap().unwrap();
        item.source = Some(LearningSource {
            kind: SourceKind::Chat,
            conversation_id: Some("source-chat".into()),
            message_ids: vec!["message".into()],
            snapshot: "Original independent source".into(),
            asset_ids: vec!["owned-asset".into()],
        });
        store.conn.lock().unwrap().execute_batch("INSERT INTO agents(id,name,systemPrompt,createdAt,updatedAt) VALUES('source-agent','Source','',0,0);
            INSERT INTO conversations(id,title,providerId,agentId,createdAt,updatedAt) VALUES('source-chat','Source','p','source-agent',0,0);
            INSERT INTO messages(id,conversationId,role,content,timestamp,status) VALUES('message','source-chat','user','Original',0,'sent');").unwrap();
        let saved = store.upsert_learning(item, 20).unwrap();
        assert_eq!(
            saved.source.as_ref().unwrap().conversation_id.as_deref(),
            Some("source-chat")
        );
        store.delete_conversation("source-chat").unwrap();
        let detached = store.get_learning("item").unwrap().unwrap().source.unwrap();
        assert!(detached.conversation_id.is_none());
        assert!(detached.message_ids.is_empty());
        store.clear_agents_for_reinit().unwrap();
        let source = store.get_learning("item").unwrap().unwrap().source.unwrap();
        assert_eq!(source.snapshot, "Original independent source");
        assert_eq!(source.asset_ids, vec!["owned-asset"]);
        store.clear_learning().unwrap();
        assert_eq!(store.learning_overview(50).unwrap().items, 0);
    }
    #[test]
    fn resource_metadata_and_learning_import_share_atomic_transaction() {
        let source = Store::open_memory().unwrap();
        fixture(&source);
        let package = source.export_learning().unwrap();
        let dest = Store::open_memory().unwrap();
        let entries = vec![
            ("local_asset:image".into(), "owned".into()),
            ("artifact:page".into(), "{}".into()),
        ];
        dest.import_learning_with_local_kv(&package, false, &entries)
            .unwrap();
        assert!(dest.kv_get("local_asset:image").unwrap().is_none());
        assert_eq!(dest.learning_overview(10).unwrap().items, 0);
        // A conflict discovered after writing the asset entry rolls back it and all learning rows.
        dest.kv_set("artifact:page", "existing").unwrap();
        assert!(matches!(
            dest.import_learning_with_local_kv(&package, true, &entries),
            Err(LearningError::Conflict(_))
        ));
        assert!(dest.kv_get("local_asset:image").unwrap().is_none());
        assert_eq!(dest.learning_overview(10).unwrap().items, 0);
        dest.kv_delete("artifact:page").unwrap();
        dest.import_learning_with_local_kv(&package, true, &entries)
            .unwrap();
        assert_eq!(
            dest.kv_get("local_asset:image").unwrap().as_deref(),
            Some("owned")
        );
        assert_eq!(dest.learning_overview(10).unwrap().items, 1);
        let empty = Store::open_memory().unwrap();
        assert!(empty
            .import_learning_with_local_kv(
                &package,
                true,
                &[("cloud_session".into(), "secret".into())]
            )
            .is_err());
        assert_eq!(empty.learning_overview(10).unwrap().items, 0);
    }
    #[test]
    fn v7_migration_reopen_and_package_atomic_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("core.sqlite");
        {
            let conn = Connection::open(&path).unwrap();
            for sql in crate::schema::CREATE_TABLES {
                conn.execute_batch(sql).unwrap();
            }
            conn.pragma_update(None, "user_version", 7).unwrap();
        }
        {
            let store = Store::open(&path).unwrap();
            assert_eq!(store.data_dir().as_deref(), Some(dir.path()));
            fixture(&store);
            store
                .submit_learning_review(submission(), 20, scheduler)
                .unwrap();
        }
        let store = Store::open(&path).unwrap();
        assert_eq!(
            store
                .conn
                .lock()
                .unwrap()
                .query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            crate::schema::SCHEMA_VERSION
        );
        let package = store.export_learning().unwrap();
        let dest = Store::open_memory().unwrap();
        dest.import_learning(&package, false).unwrap();
        assert_eq!(dest.learning_overview(100).unwrap().items, 0);
        dest.import_learning(&package, true).unwrap();
        assert_eq!(dest.learning_attempts("card", 0, 20).unwrap().len(), 1);
        assert_eq!(
            dest.get_learning("item").unwrap(),
            store.get_learning("item").unwrap()
        );
        assert!(matches!(
            dest.import_learning(&package, true),
            Err(LearningError::Conflict(_))
        ));
        let mut bad = package.clone();
        bad.review_states[0].ease = 0.1;
        let empty = Store::open_memory().unwrap();
        assert!(empty.import_learning(&bad, true).is_err());
        assert_eq!(empty.learning_overview(50).unwrap().items, 0);
    }
}
fn validate_state(s: &ReviewState) -> Result<()> {
    if s.version < 0
        || s.interval_days < 0
        || s.interval_days > 36500
        || s.repetitions < 0
        || !s.ease.is_finite()
        || s.ease < 1.3
        || s.algorithm != "sm2-v1"
    {
        return Err(invalid("invalid review scheduler state"));
    }
    Ok(())
}
fn has_dependents(conn: &Connection, id: &str) -> Result<bool> {
    let mut stmt = conn.prepare("SELECT body FROM learning_records WHERE id!=?1")?;
    let bodies = stmt
        .query_map([id], |r| r.get::<_, String>(0))?
        .collect::<std::result::Result<Vec<_>, _>>()?;
    for body in bodies {
        let r: LearningRecord = decode(&body)?;
        if r.course_id.as_deref() == Some(id) || r.knowledge_point_ids.iter().any(|v| v == id) {
            return Ok(true);
        }
        if match r.content {
            LearningContent::KnowledgePoint { parent_id, .. } => parent_id.as_deref() == Some(id),
            LearningContent::Task {
                plan_id, item_id, ..
            } => plan_id == id || item_id.as_deref() == Some(id),
            LearningContent::ReviewCard { item_id, .. } => item_id == id,
            _ => false,
        } {
            return Ok(true);
        }
    }
    Ok(false)
}
