# Text to 3D

A web app that turns a text prompt into a 3D model, shows it in an interactive viewer, and lets you download it.

**Live demo:** _add your Render URL here_

## Features

- Enter a prompt describing an object
- Generates a 3D model with **Shap-E**, OpenAI's open-source text-to-3D model, running on a free Hugging Face Space
- Interactive viewer built with Three.js: drag to rotate, scroll or pinch to zoom, right-drag to pan
- Download the model as `.glb` (glTF binary: opens in Blender, Unity, Unreal and online viewers)
- Loading state and clear error messages

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3 (`RestClient`, virtual threads) |
| Frontend | Plain HTML + Three.js (`GLTFLoader`, `OrbitControls`), served by Spring Boot |
| AI model | Shap-E via the Hugging Face Space `hysts/Shap-E` (Gradio REST API) |
| Format | GLB |
| Hosting | Render (Docker, free tier) |

## Architecture

```
Browser ──POST /api/generate {prompt}──▶ Spring Boot ── starts a background job, returns taskId
                                            └─ job: Gradio API (POST → event_id, GET → SSE result)
                                                    → downloads the .glb and keeps it in memory
Browser ──GET /api/status/{id} (poll)──▶ Spring Boot ── running / success / failed
Browser ──GET /api/model/{id}─────────▶ Spring Boot ── streams the .glb (viewer + download)
```

Design decisions:

- **Async job + polling.** Generation takes 20-60 seconds, too long to hold one HTTP request open, so `/generate` returns a task id immediately, the work runs on a Java 21 virtual thread, and the frontend polls `/status`.
- **The backend talks to the model, not the browser.** This keeps the Hugging Face token on the server and avoids CORS.
- **The file is downloaded as soon as it's ready.** Files on the Space are temporary, so the job stores the `.glb` bytes and `/api/model/{id}` serves them for both the viewer and the download (`?download=true` sets `Content-Disposition: attachment`).
- **Bounded in-memory storage.** Finished jobs are cleared after 50 to stay within the free tier's memory.
- **One deployable.** The frontend is a static page inside the Spring Boot app: one service, no separate frontend hosting.

Why Shap-E on a Hugging Face Space: it's free and open-source, with no paid API credits needed. The trade-off is that free GPUs are shared, so a request can occasionally fail when the GPU quota is busy; the app shows a clear "try again" message when that happens. Commercial APIs (Meshy, Tripo) give higher-quality textured models and would slot in by replacing one client class.

## API

| Method | Path | Description |
|---|---|---|
| POST | `/api/generate` | Body `{"prompt": "..."}` → `{"taskId": "..."}` |
| GET | `/api/status/{taskId}` | → `{status, ready, failed, error}` |
| GET | `/api/model/{taskId}` | → `.glb` file (`?download=true` to force download) |

## Run locally

Requires Java 21 and Maven. A free Hugging Face token (https://huggingface.co/settings/tokens, "Read" access) is recommended for a higher GPU quota.

Create a `.env` file next to `pom.xml`:

```
HF_TOKEN=hf_your_token
```

```bash
mvn spring-boot:run
```

Open http://localhost:8080.

## Deploy (Render)

1. Push this repo to GitHub.
2. On Render: **New → Web Service**, connect the repo, language **Docker**, instance **Free**.
3. Add environment variable `HF_TOKEN`.
4. Deploy.

Render's free tier sleeps after inactivity, so the first request can take ~50 seconds to wake the service.

## Possible improvements

- Swap in a commercial API (Meshy/Tripo) for textured, higher-detail models
- Persist jobs and models (Postgres + S3) so users can revisit past results
- More export formats (OBJ, STL)
- Rate limiting per IP
