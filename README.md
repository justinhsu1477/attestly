# Attestly

> 為 Spring AI 而生的 Java-native 記憶層。每一筆記憶都帶 audit trail，原生支援 MCP。

[![Maven Central](https://img.shields.io/maven-central/v/io.github.justinhsu1477/attestly-spring-boot-starter)](#)
[![PyPI](https://img.shields.io/pypi/v/attestly)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![CI](https://github.com/justinhsu1477/attestly/workflows/CI/badge.svg)](#)
[![Stars](https://img.shields.io/github/stars/justinhsu1477/attestly?style=social)](#)

---

## ✨ 為什麼存在這個東西

Spring AI 內建的 `ChatMemory` 只是個 in-memory list — app 重啟就忘光。要把 LLM app 推上 production，你需要：

| 需求 | Spring AI 原生 | mem0 / Letta | **Attestly** |
| --- | :---: | :---: | :---: |
| Persistent memory | ❌ | ✅ | ✅ |
| Java/Spring 原生 | ⭐ 陽春 | ❌ | ✅ |
| Audit trail（source、quote、confidence、retention） | ❌ | ❌ | ✅ |
| MCP-native（Claude Desktop / Cursor 直接用） | ❌ | ❌ | ✅ |
| 多語言 client（Java + Python + TS） | ❌ | Python only | ✅ |

如果你的 AI app 要上 production、又要過 compliance review、又要跟 Python 同事共用同一份記憶 — Attestly 是為這個情境而生的。

---

## 🤔 那我自己寫一個 audit table 不就好了？

對。`CREATE TABLE memories (...)` 你 15 分鐘就寫得出來。

但 **production-grade AI memory 從來不只是 schema**：

| 你以為要寫的 | 實際還要處理的 |
| --- | --- |
| `CREATE TABLE memories` | embedding API rate limit、retry、cache（不能每次都重 embed） |
| `pgvector` similarity search | L2 vs cosine、hybrid search、reranking |
| 加幾個 audit 欄位 | confidence 怎麼算？retention 怎麼自動執行？`explain()` API 怎麼設計？ |
| 記什麼就存什麼 | fact extraction、dedup、conflict resolution、eviction（不做的話兩週後 100 萬筆，search 變慢） |
| OpenAI embedding | model 升級時舊資料怎麼 migrate（維度不同根本不能比對） |
| `user_id` 隔離 | GDPR 右刪除權、跨用戶搜尋防護 |
| 跟 Claude Desktop 共享 | 學 MCP 協定 + 寫 server + 維護 |
| Python 同事也要用 | 寫 Python client + TS client + 維持 schema 同步 |
| 上線後 6 個月 | bugs、依賴升級、新 embedding model、新 vector DB — 誰維護？ |

**寫一個能跑的 = 15 分鐘。寫一個 production-grade 的 = 6-9 個月。**

這跟你「為什麼用 Hibernate 不自己寫 JDBC」、「為什麼用 Spring Security 不自己 hash password」、「為什麼用 Spring Boot 不自己寫 Servlet」是同一個問題 — **不是不能寫，是寫了你會後悔**。

Attestly 把那 99% 的隱藏工作包好，給你 `@EnableMemoryLayer` 一個 annotation 就完事。

> **什麼情況該自己寫**：內部 5 人用的 prototype、< 1000 筆 memory、不在意 audit。
> **什麼情況該用 Attestly**：JVM + production + 有 audit / compliance 需求 + 想跟 MCP 生態互通。

---

## 🚀 30 秒上手

### Spring Boot（Kotlin / Java）

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.justinhsu1477:attestly-spring-boot-starter:0.1.0")
}
```

```yaml
# application.yml
attestly:
  vector-store: pgvector
  embedding: openai
  audit.enabled: true
```

```kotlin
@Component
class MyAssistant(private val memory: MemoryLayer) {
    fun chat(userId: String, message: String): String {
        val context = memory.search(userId, message, k = 5)
        val response = llm.complete(message, context)
        memory.add(userId, message, source = "chat:$userId")
        return response
    }
}
```

完成。AI 現在會記住每個用戶的對話，每筆都帶 source / quote / 信心度。

### Python

```bash
pip install attestly
```

```python
from attestly import MemoryClient

mem = MemoryClient(base_url="http://localhost:8080")
mem.add(user_id="u1", text="I prefer sci-fi novels", source="chat")
results = mem.search(user_id="u1", query="book recommendations", k=5)
```

### Claude Desktop（MCP）

```json
{
  "mcpServers": {
    "attestly": {
      "command": "uvx",
      "args": ["attestly-mcp", "--db-url", "postgresql://..."]
    }
  }
}
```

Claude 現在可以查詢你的記憶層 — 跟你的 Spring Boot app 看到同一份資料。

---

## 三個殺手 feature

### 1️⃣ Spring AI 整合到位

- Spring Boot Starter + autoconfig，零 boilerplate
- `@EnableMemoryLayer` 一鍵啟用
- 跟 `ChatClient` / `VectorStore` / `EmbeddingModel` 原生組合
- `application.yml` 配置全部走 Spring 慣例

### 2️⃣ Audit trail 內建（核心差異化）

每筆 memory 不只是 text + embedding，還有完整出處：

```json
{
  "id": "mem_abc123",
  "user_id": "u1",
  "text": "User prefers sci-fi novels",
  "source": "chat:session_xyz",
  "original_quote": "I just finished Project Hail Mary, loved it",
  "confidence": 0.87,
  "retention_policy": "365_days",
  "created_at": "2026-05-25T10:30:00Z",
  "embedding_model": "text-embedding-3-small"
}
```

當用戶（或 compliance team）問「你怎麼知道我喜歡科幻？」：

```kotlin
val explanation = memory.explain("mem_abc123")
// → MemoryExplanation(
//     learnedAt = 2026-05-25T10:30:00Z,
//     source = "chat:session_xyz",
//     originalQuote = "I just finished Project Hail Mary, loved it",
//     confidence = 0.87,
//     retentionExpiresAt = 2027-05-25
//   )
```

**這是 mem0 / Letta 都沒有的功能**。

### 3️⃣ MCP-native，不是 MCP-bolted

MCP server 是 first-class citizen，不是事後加的 wrapper：

- 同一份 Postgres 資料，Spring Boot 跟 Claude Desktop 共享
- MCP server 暴露完整 `memory.search` / `memory.add` / `memory.explain` / `memory.forget`
- 不用部署兩套後端，不用同步資料

---

## 🏗️ 架構

```
┌──────────────────────────────────────────────────────────┐
│                  Your AI Application                      │
├──────────────┬──────────────────┬─────────────────────────┤
│ Spring Boot  │  Python apps     │ Claude Desktop /        │
│ (Kotlin/Java)│  (LangChain etc) │ Cursor / Any MCP client │
└──────┬───────┴──────┬───────────┴──────────┬──────────────┘
       │              │                       │
       │         ┌────▼────┐         ┌────────▼────────┐
       │         │ Python  │         │  MCP Server     │
       │         │ Client  │         │  (Python)       │
       │         │ (HTTP)  │         │                 │
       │         └────┬────┘         └────────┬────────┘
       │              │                       │
       └──────────────┼───────────────────────┘
                      │
              ┌───────▼───────┐
              │ Kotlin Core   │
              │ ─────────────│
              │ Memory API    │
              │ Audit Layer   │
              │ Embedding Cl. │
              │ Spring Starter│
              └───────┬───────┘
                      │
              ┌───────▼───────┐
              │   pgvector    │
              │  (PostgreSQL) │
              └───────────────┘
```

---

## 📦 安裝

| Stack | 安裝指令 |
| --- | --- |
| Spring Boot (Gradle) | `implementation("io.github.justinhsu1477:attestly-spring-boot-starter:0.1.0")` |
| Spring Boot (Maven) | `<dependency><groupId>io.github.justinhsu1477</groupId><artifactId>attestly-spring-boot-starter</artifactId><version>0.1.0</version></dependency>` |
| Python | `pip install attestly` |
| MCP server | `uvx attestly-mcp` |
| TypeScript / Node | `npm install attestly` |

**需求**：JDK 17+ / Python 3.10+ / PostgreSQL 14+ with pgvector extension。

---

## ⚙️ 設定

最小設定：

```yaml
attestly:
  vector-store:
    type: pgvector
    url: ${DB_URL}
  embedding:
    provider: openai
    api-key: ${OPENAI_API_KEY}
    model: text-embedding-3-small
  audit:
    enabled: true
    retention-default-days: 365
```

完整選項見 [`docs/configuration.md`](docs/configuration.md)（v0.1 補完）。

---

## 🎬 範例

| 範例 | Stack | 連結 |
| --- | --- | --- |
| Spring Boot 客服機器人 | Spring AI + Kotlin | [`examples/spring-chatbot`](examples/spring-chatbot) |
| Python LangChain agent | Python + LangChain | [`examples/python-langchain`](examples/python-langchain) |
| Claude Desktop 整合 | MCP | [`examples/claude-desktop`](examples/claude-desktop) |
| Cursor 整合 | MCP | [`examples/cursor`](examples/cursor) |

---

## 🗺️ Roadmap

### v0.1 — MVP（5-7 週內 ship）
- [ ] Kotlin core + Spring Boot Starter
- [ ] pgvector backend
- [ ] OpenAI embedding
- [ ] Audit trail（source / quote / confidence / retention）
- [ ] `memory.explain()` API
- [ ] Python client（HTTP）
- [ ] MCP server（Python）
- [ ] Demo app（Spring chatbot + audit UI）

### v0.2 — 多後端
- [ ] SQLite 本機模式
- [ ] Qdrant / Pinecone backend
- [ ] Ollama 本機 embedding
- [ ] TypeScript client

### v0.3 — Production
- [ ] Multi-tenant permissions / scopes
- [ ] Retention policy 自動執行
- [ ] Prometheus metrics
- [ ] OpenTelemetry tracing

### v0.5+
- [ ] Hosted SaaS（可選）
- [ ] Web admin dashboard

---

## 🤝 Contributing

歡迎 PR、issue、discussion。請先讀 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

特別歡迎這幾類貢獻：
- Vector store adapter（Qdrant、Pinecone、Weaviate、Milvus）
- Embedding provider adapter（Azure、Ollama、Cohere、Voyage）
- 整合範例（Spring AI、LangChain、LlamaIndex、Haystack）

---

## 📄 License

MIT © 2026 [Justin Hsu](https://github.com/justinhsu1477)

---

## 🙏 Acknowledgments

- [Spring AI](https://docs.spring.io/spring-ai/reference/) — 為 Java 圈把 AI 帶到桌上
- [OpenHuman](https://github.com/tinyhumansai/openhuman) — MCP 實作參考
- [OpenKindred](https://github.com/Zavianx/OpenKindred) — emotional memory 概念啟發
- [mem0](https://github.com/mem0ai/mem0) / [Letta](https://github.com/letta-ai/letta) — 前輩在 Python 圈鋪的路
