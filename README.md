# GhidraLLM: LLM-assisted renaming for Ghidra 12.1.4

GhidraLLM sends functions to a large language model and applies the names it proposes for the **function, its parameters and its local variables**. It also has a **chat window** where you can ask the model about the code.

## Features

| Area | What you get |
|---|---|
| Scope | Rename the current function, **every** function, functions in the current selection, or a **filtered list** (globs such as `FUN_*`, `sub_40????`, `!FUN_0040*` to exclude, or regex) with a checkbox table to review matches |
| Providers | Defaults for **Claude** (Anthropic), **Gemini**, **Meta** (Llama API), **GitHub Copilot** (GitHub Models API) and **Ollama**. **Add New Provider** works for any endpoint that speaks the Anthropic Messages, Gemini generateContent, OpenAI Chat Completions, **OpenAI Responses** or Ollama format (OpenAI, Azure, OpenRouter, Groq, LM Studio, vLLM, corporate gateways, ...) |
| Endpoints | **Base URL + endpoint path**. The path is an editable drop-down of common paths for the API type, and you can also type any custom path (query strings and `{model}` allowed). A live **Full URL** preview shows the exact address. *Paste Full URL...* splits a complete URL for you. The models-list path is configurable the same way |
| Selection | `GhidraLLM > Select LLM > ...` (checkable list plus *Add New Provider...*), the ★ in the config dialog, or the drop-down in the chat window |
| Code sent | Decompiled C, disassembly, or both (target and callers/callees can use different modes) |
| Xrefs | Caller depth and callee depth (how many levels to follow), max callers/callees per level, and character budgets |
| Limits | Max code chars (default **12,000**), max caller/callee chars (default **125,000**), per-neighbor cap, decompile timeout |
| Network | HTTP timeout (default **180 s**), connect timeout, **retries 0–20,000** for 429/5xx (spinner with arrows, or type the value), exponential backoff with jitter, honors `Retry-After` |
| Model | Optional temperature (off unless ticked), max output tokens, **context window (default 1,000,000)**, optional JSON mode, extra headers, extra body JSON |
| PKI | Optional client certificate (**.p12 / .pfx** + password) and **CA certificate (.cer/.crt/.pem, DER or PEM)**, with or without Java's default CAs. Includes *Inspect Certificates* and *Test Connection* buttons |
| Safety | Preview/confirm dialog for single functions (editable names, per-variable checkboxes), one undoable transaction per function, user-defined names are kept unless you opt in, batch stops on 4xx configuration errors |
| Chat | Dockable chat. Can attach the current function and, optionally, its callers/callees. Multi-turn, trimmed to the context window |

## Install

1. In Ghidra: **File > Install Extensions... > +** and pick `ghidra_12.1.4_PUBLIC_*_GhidraLLM.zip`.
2. Restart Ghidra.
3. Open a program in the CodeBrowser. If Ghidra asks about new plugins, enable **GhidraLLMPlugin**. If it doesn't, go to **File > Configure > Miscellaneous** and tick it.
4. A **GhidraLLM** menu appears in the CodeBrowser menu bar.

## First-time setup

Open **GhidraLLM > LLM Configuration...**. By default, API keys are read from environment variables so they stay out of the config file:

| Provider | Default key setting | Notes |
|---|---|---|
| Claude | `env:ANTHROPIC_API_KEY` | Anthropic Messages API |
| Gemini | `env:GEMINI_API_KEY` | Google AI Studio key |
| Meta | `env:LLAMA_API_KEY` | Meta Llama API (OpenAI-compatible endpoint) |
| GitHub Copilot | `env:GITHUB_TOKEN` | GitHub's official **GitHub Models** inference API (`models.github.ai`). Use a GitHub personal access token with the **models: read** permission. Model IDs look like `openai/gpt-4.1` |
| Ollama | none | `http://localhost:11434/api/chat` |

You can also paste a key directly. Any key, header value or certificate password can be written as `env:NAME`. Set environment variables before you start Ghidra, e.g. `setx ANTHROPIC_API_KEY sk-...` on Windows, then restart Ghidra.

Use **Fetch Models** to fill the model list from the provider, and **Test Connection** to check the key, TLS and PKI settings.

The settings are saved to `%USERPROFILE%\.ghidrallm\config.json` (or `~/.ghidrallm/config.json`). You can override the location with `-Dghidrallm.config=<path>`. If the file is corrupt, it is backed up and defaults are loaded.

## Endpoints (base URL + path)

Nothing is silently appended: the request goes to **Base URL + Endpoint path**, exactly as shown in the read-only *Full URL* line.

| Field | Example | Notes |
|---|---|---|
| Base URL | `https://api.llama.com/compat`, `https://my-gw.corp:8443/llm` | Scheme, host, port and any path prefix |
| Endpoint path | `/v1/chat/completions`, `/chat/completions`, `/openai/deployments/{model}/chat/completions?api-version=2024-10-21`, `/v1/responses`, `/v1/messages`, `/api/chat` | Pick a preset or type any path. `{model}` is replaced with the (URL-encoded) model name. A full `https://...` URL here overrides the base |
| Models path | `/v1/models`, `https://models.github.ai/catalog/models` | Used only by *Fetch Models*. Leave blank for the type's default |

The **API type** chooses the request/response format and the preset paths. The path is independent of the type, so a gateway that speaks the OpenAI chat format at `/llm/v2/converse` works with type *OpenAI-compatible* and that path typed in. Use **OpenAI Responses API** for `/v1/responses`-style endpoints, which need a different JSON format (`instructions`/`input`/`output`). Configs saved by the earlier build, which had a single URL field, are split into base + path automatically.

## Using it

* **Rename one function**: put the cursor in a function, then use **GhidraLLM > Rename Current Function** or right-click in the Listing/Decompiler and choose **GhidraLLM > Rename Function with LLM**. When preview is enabled, you can edit or untick suggestions before you press **Apply**.
* **Filtered**: **GhidraLLM > Rename Filtered Functions (e.g. FUN_*)...**. Enter patterns, review the matches, then press **Rename Selected**.
* **All**: **GhidraLLM > Rename All Functions...** (thunks and externals are skipped).
* **Selection**: **GhidraLLM > Rename Functions in Selection**.
* **Chat**: **GhidraLLM > Chat with LLM...** (Ctrl+Enter sends).
* **Activity Log**: shows every result, retry and error.

Batch runs process callees before callers by default (bottom-up), so callers see the new callee names. *Concurrent requests* lets you run several LLM calls in parallel; watch your rate limits. To undo, use Ghidra's **Edit > Undo**, which reverts one function per step.

## Analysis & Context settings

| Setting | Default | Meaning |
|---|---|---|
| Target function as | Decompiled C | Decompiled, disassembly, or both. Variables are renamed in every mode (the function is still decompiled in the background to get its variables) |
| Callers/callees as | Same as target | Or decompiled, disassembly, or signatures only |
| Caller depth / callee depth | 1 / 1 | Levels of xrefs to follow, up to 50 |
| Max callers / callees per level | 10 / 20 | Breadth limit per level |
| Max code characters | 12,000 | Target function code |
| Max caller/callee characters | 125,000 | Total neighbor budget |
| Max characters per caller/callee | 6,000 | One neighbor's body |
| Decompile timeout | 60 s | Per function |
| Strings / imports | on | Referenced strings and external API names |
| Overwrite user-defined names | off | When off, only `FUN_*`-style function names and `param_N`/`local_xx`/`uVarN`-style variables are changed |
| Additional instructions | — | Appended to the system prompt (e.g. "use Hungarian notation", "this is a Linux kernel module") |

The context window also caps the prompt: GhidraLLM estimates about 3.5 characters per token and trims caller/callee context first.

## PKI notes

* **Client certificate**: a PKCS#12 file (`.p12`/`.pfx`) that contains the private key. The password may be `env:VAR`.
* **CA certificate**: one or more X.509 certificates in PEM or DER form (`.cer`, `.crt`, `.pem`). With **Also trust Java's default CAs** unticked, only that CA is trusted, which suits internal gateways.
* TLS failures (unknown CA, rejected client certificate) are not retried, because retrying won't fix them.

## Building from source

```
set GHIDRA_INSTALL_DIR=C:\path\to\ghidra_12.1.4_PUBLIC
gradle
```
The zip is written to `dist\`. Gradle requirements come from your Ghidra install (Gradle 8.5+, JDK 21+).

## Tests

`tests/java` holds standalone JUnit 4 tests for the JSON response parser, retry/backoff (429/500/503, Retry-After, cancellation, the 20,000 cap), each provider's request/response format, model listing, config persistence, and end-to-end **mutual TLS** against a local HTTPS server (.pfx client certificate plus PEM and DER CA). They need Gson, JUnit 4 and BouncyCastle on the classpath. All of these ship with Ghidra except JUnit.

## Layout

```
src/main/java/ghidrallm/
  GhidraLLMPlugin.java        menus, actions, rename flows
  config/                     ProviderConfig, AnalysisSettings, ConfigStore (JSON)
  llm/                        HTTP + retries, PKI SSLContext, provider wire formats
  analysis/                   decompile/disassemble, xref context, prompt, parser, rename applier, batch task
  ui/                         config dialog, filter dialog, preview dialog, chat and log windows
```
