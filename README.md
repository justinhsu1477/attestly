# Attestly

> 為 Spring AI 而生的 Java-native 記憶層 — 每一筆記憶都帶 audit trail。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> ⚠️ **v0.1 開發中。** 目前只有 Kotlin `core` 模組可用。Spring Boot Starter、pgvector / OpenAI adapter、Python client、MCP server 都還在路上 — 下面標 🚧 的功能尚未實作,詳見 [Roadmap](#roadmap)。

---

## 為什麼存在

Spring AI 內建的 `ChatMemory` 只是個 in-memory list — app 重啟就忘光。要把 LLM app 推上 production,你需要持久化、稽核出處、跨語言共享同一份記憶。Attestly 想補的就是這塊:

| | Spring AI 原生 | mem0 / Letta | Attestly |
| --- | :---: | :---: | :---: |
| Persistent memory | ❌ | ✅ | 🚧 |
| Java / Spring 原生 | ⭐ 陽春 | ❌ | ✅ |
| Audit trail（source / quote / confidence / retention） | ❌ | ❌ | ✅ |
| MCP-native | ❌ | ❌ | 🚧 |
| 多語言 client（Java + Python + TS） | ❌ | Python only | 🚧 |

自己寫一個能跑的 audit table 只要 15 分鐘;但 embedding 的 rate limit / retry / cache、retention 自動執行、embedding model 升級時的維度遷移、GDPR 刪除權、MCP 協定、多語言 client 同步 — 要做到 production-grade 是另一回事。Attestly 的目標是把這些一次包好。

- **適合**:JVM + production + 有 audit / compliance 需求。
- **不適合**:內部 prototype、少於 1000 筆記憶、不在意稽核 — 自己寫就好。

---

## 目前能用什麼

**✅ 已完成 — `core` 模組（純 Kotlin,零框架依賴）**

- `Memory` domain model + 完整 audit 欄位:`source` / `originalQuote` / `confidence` / `retentionPolicy`
- `MemoryService`:`add` / `search` / `explain` / `forget` 四個操作
- 多租戶隔離 + 反列舉:跨租戶的查詢、刪除、explain 一律回傳 `null` / `false`,不洩漏其他人的 memory id
- 可插拔的 `MemoryRepository` 與 `Embedder` ports(Ports & Adapters)
- `RetentionPolicy` 與設定字串解析(`365d` / `4w` / `6m` / `forever`)
- `EmbeddingModelTag`:記錄每筆向量由哪個 model 產生,避免升級後維度不符的靜默錯誤

**🚧 規劃中 — 見 [Roadmap](#roadmap)**

- pgvector / SQLite repository、OpenAI / Ollama embedder adapter
- Spring Boot Starter(`@EnableMemoryLayer` + autoconfig)
- Python client、MCP server、TypeScript client
- 範例 app 與完整文件

---

## Core API 長這樣

`core` 定義了純 Kotlin 的記憶契約,不綁任何框架。實際儲存與向量化由你提供的 `MemoryRepository` / `Embedder` 實作決定(production adapter 規劃中):

```kotlin
val service = MemoryService(
    repository = myRepository,   // 你的 pgvector / SQLite 實作
    embedder = myEmbedder,       // 你的 OpenAI / Ollama 實作
)

// 記住一筆事實,帶完整出處
val mem = service.add(
    userId = UserId("u1"),
    text = "User prefers sci-fi novels",
    source = "chat:session_xyz",
    originalQuote = "I just finished Project Hail Mary, loved it",
    confidence = Confidence(0.87),
    retentionPolicy = RetentionPolicy.parse("365d"),
)

// 語意搜尋,結果只限這個 user
val hits = service.search(UserId("u1"), query = "book recommendations", k = 5)

// 稽核:這筆記憶到底哪來的?
val why = service.explain(mem.id, requestingUserId = UserId("u1"))
println(why?.toHumanReadable())
// → I learned this on 2026-05-25T10:30:00Z from chat:session_xyz when you said:
//   "I just finished Project Hail Mary, loved it" (confidence 0.87, kept until 2027-05-25).
```

---

## 核心差異化:Audit trail 內建

每筆 memory 不只是 text + embedding,還帶完整出處,直接回答 compliance team 會問的四個問題:

| 問題 | 對應欄位 |
| --- | --- |
| 這從哪來? | `source` |
| 用戶原話是什麼? | `originalQuote` |
| 有多確定? | `confidence` |
| 留多久? | `retentionPolicy` |

`explain()` 把這些組成一張可讀的稽核收據 —— **這是 mem0 / Letta 都沒有的功能**。多租戶隔離與反列舉也在這一層強制執行:跨租戶存取一律拒絕,且不區分「不存在」與「不屬於你」,避免被拿來枚舉其他人的 memory id。

> 🚧 **Spring AI 整合**(starter + autoconfig)與 **MCP server**(讓 Claude Desktop / Cursor 跟你的 Spring Boot app 共享同一份 Postgres 記憶)是 Attestly 的兩個主要目標,目前規劃中。

---

## Roadmap

### v0.1 — MVP
- [x] Kotlin core(domain + service + ports + audit + `explain`)
- [ ] pgvector backend
- [ ] OpenAI embedding
- [ ] Spring Boot Starter
- [ ] Python client（HTTP）+ MCP server
- [ ] Demo app（Spring chatbot + audit UI）

### v0.2 — 多後端
- [ ] SQLite 本機模式
- [ ] Qdrant / Pinecone backend
- [ ] Ollama 本機 embedding
- [ ] TypeScript client

### v0.3 — Production
- [ ] Multi-tenant scopes / permissions
- [ ] Retention policy 自動執行（purge job）
- [ ] Prometheus metrics + OpenTelemetry tracing

### v0.5+
- [ ] Hosted SaaS（可選）、Web admin dashboard

---

## 開發

需求:JDK 17+。

```bash
./gradlew :core:test    # 跑核心測試
./gradlew build         # 編譯全部
```

歡迎 PR、issue、discussion,先讀 [CONTRIBUTING.md](CONTRIBUTING.md)。特別需要:vector store adapter、embedding provider adapter、整合範例。

---

## License

MIT © 2026 [Justin Hsu](https://github.com/justinhsu1477)

## Acknowledgments

- [Spring AI](https://docs.spring.io/spring-ai/reference/) — 為 Java 圈把 AI 帶到桌上
- [mem0](https://github.com/mem0ai/mem0) / [Letta](https://github.com/letta-ai/letta) — 前輩在 Python 圈鋪的路
- [OpenHuman](https://github.com/tinyhumansai/openhuman) — MCP 實作參考
