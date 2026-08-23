# 10 — Scalability Plan

How VN Code scales from one developer to millions, on both the device and
the cloud.

---

## 1. On-device scalability (the hard part for mobile)
The app must stay smooth on 3 GB phones with huge repos.

- **Large files:** piece-table / rope buffer; viewport-only tokenization; memory-mapped
  reads; refuse-then-confirm dialog above a configurable threshold (no *hard* limit).
- **Huge trees:** paged, lazy file tree; directory listings cached in Room with etags;
  search uses an incremental indexer (ripgrep-style) instead of loading files.
- **Many tabs:** inactive editor sessions are serialized to Room and their buffers freed;
  rehydrated on focus.
- **Background work:** WorkManager batches sync/backup/embedding; respects Doze, battery,
  and metered-network constraints.
- **Memory budget manager:** subscribes to `onTrimMemory`; sheds caches progressively.

## 2. Backend horizontal scalability
- **Stateless API pods** behind a load balancer → scale via HPA (CPU + RPS).
- **WebSocket tier separated** (terminal/sync) from REST so long-lived connections don't
  block request throughput; sticky sessions or a shared Redis pub/sub for fan-out.
- **Database:** managed Postgres with read replicas; route reads to replicas; PgBouncer
  connection pooling; partition high-volume tables (`logs`, `ai_usage`) by time.
- **Cache:** Redis for sessions, rate limits, hot project metadata, and WS pub/sub.
- **Queues:** BullMQ workers scale independently for backups & embeddings.
- **Object storage:** S3/GCS for backups — effectively unbounded, cheap.

## 3. Evolving to microservices
The modular monolith splits cleanly along module seams when load demands:
```
monolith → [auth-svc] [github-svc] [ai-gateway] [terminal-svc] [sync-svc] [plugin-registry]
```
- Communicate via gRPC/REST + Redis/NATS events.
- AI gateway is the first to extract (CPU/IO heavy, independent scaling, provider fan-out).
- Terminal service extracted next (stateful, needs node pools with cgroups).

## 4. Terminal / compute scaling
- PTY sessions run in ephemeral containers on a dedicated node pool with cgroup CPU/mem/
  time caps; autoscale the pool on active-session count.
- Idle sessions reaped; per-user concurrency caps prevent abuse (configurable, generous).

## 5. AI scaling & cost control
- Provider-agnostic gateway with **per-provider rate limiting, batching, and caching** of
  identical embedding/explain requests.
- Streaming reduces memory; backpressure to clients.
- Local **Ollama** option offloads cost entirely to user hardware/self-host.
- Usage metering (`ai_usage`) enables analytics — but **no subscription gating** is built
  in, per requirements (quotas are optional and operator-configurable).

## 6. Data growth
- Time-partition + roll off `logs`/`ai_usage` to cold storage.
- Backups deduplicated by checksum; lifecycle policies to Glacier-class storage.
- Embeddings sharded per project; pgvector or external vector DB (Qdrant) when large.

## 7. Reliability targets
| Metric | Target |
|--------|--------|
| API availability | 99.9% |
| p95 REST latency | < 200 ms |
| WS reconnect success | > 99% |
| Sync conflict rate | < 0.5% of pushes |
| Crash-free sessions (app) | > 99.5% |

## 8. Capacity milestones
| Stage | Users | Setup |
|-------|-------|-------|
| MVP | < 1k | Single API + managed Postgres + Redis (docker compose / 1 small k8s) |
| Growth | 1k–100k | HPA API, read replica, separate WS tier, queue workers |
| Scale | 100k–1M+ | Microservices (AI/terminal extracted), partitioning, multi-AZ, CDN |
| Global | > 1M | Multi-region, geo-routed API, regional DB, edge caching |

## 9. Cost levers
- ABI-split APKs + on-demand runtime downloads keep distribution light.
- Local-first design pushes most compute to the device → cheap cloud footprint.
- Spot/preemptible nodes for terminal & batch workers; autoscale to zero off-peak.
