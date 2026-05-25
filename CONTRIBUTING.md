# Contributing to Attestly

歡迎 PR、issue、discussion！Attestly 是 OSS，所有貢獻都讓它更好。

## 🚀 我可以怎麼幫忙？

特別歡迎這幾類貢獻（v0.1 期間）：

- **Vector store adapter** — Qdrant、Pinecone、Weaviate、Milvus、Chroma
- **Embedding provider adapter** — Azure OpenAI、Ollama、Cohere、Voyage AI、Google
- **整合範例** — Spring AI、LangChain、LlamaIndex、Haystack 對接
- **語言 client** — 補完 TypeScript、Go、Ruby、PHP client
- **文件翻譯** — 英文版 README、API 文件
- **Bug report** — 找到不該動的東西別客氣開 issue

## 🏗️ 專案結構

```
attestly/
├── core/                    # Kotlin core (memory API + audit layer)
├── spring-boot-starter/     # Spring Boot autoconfig
├── clients/
│   ├── python/              # Python HTTP client
│   └── typescript/          # TypeScript HTTP client (v0.2)
├── mcp-server/              # Python MCP server
├── examples/
│   ├── spring-chatbot/
│   ├── python-langchain/
│   └── claude-desktop/
└── docs/                    # 文件
```

## 🛠️ 本機開發

### Prerequisites
- JDK 17+
- Python 3.10+
- PostgreSQL 14+ with `pgvector` extension
- Docker（可選，用 testcontainers 跑整合測試）

### Core (Kotlin)
```bash
cd core
./gradlew build
./gradlew test
```

### Python client / MCP server
```bash
cd clients/python  # 或 mcp-server
pip install -e ".[dev]"
pytest
```

## 📝 開 PR 之前

1. Fork → 開 feature branch（`feat/<short-desc>` 或 `fix/<short-desc>`）
2. 寫 / 改 code 時記得加 / 更新測試
3. 跑過 `./gradlew check`（Kotlin）或 `pytest`（Python）
4. Commit message 用 conventional commits 風格：`feat:` / `fix:` / `docs:` / `test:` / `refactor:`
5. PR description 包含：what / why / how to verify

## 🤝 行為準則

對人友善、對程式碼嚴格。Disagree 用技術論點，不是人身。

## 📄 License

Contribution 同 Attestly 一樣是 MIT。送 PR 即視同同意。

---

有問題開 [discussion](https://github.com/justinhsu1477/attestly/discussions) 或直接 ping maintainer。
