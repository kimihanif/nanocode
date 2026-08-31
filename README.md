# nanocode

Minimal Claude Code alternative. Single Java file, built with Maven, zero..eh..1 json dependency.

Built using Claude Code, then used to build itself.

![screenshot](screenshot.png)

## Features

- Full agentic loop with tool use
- Tools: `read`, `write`, `edit`, `glob`, `grep`, `bash`
- Conversation history
- Colored terminal output

## Requirements

- JDK 21+
- Maven

## Quick start

No API key needed — the default provider is Antigravity, which signs in with your Google account
(and uses your Google AI Pro subscription credits if you have one):

```bash
mvn -q package
java -jar target/nanocode.jar
```

On first run a browser opens for Google sign-in; approve it and start typing. Tokens are cached at
`~/.gemini/oauth_creds_antigravity.json`, so later runs start silently. Works on macOS, Linux, and
Windows (if no browser opens, copy the printed URL into one manually; port 51121 must be free
during login).

To use an API key instead, pick a provider explicitly:

```bash
export GEMINI_API_KEY="your-key"   # or ANTHROPIC_API_KEY / OPENROUTER_API_KEY
java -jar target/nanocode.jar --provider=gemini
```

Then type what you want done at the `❯` prompt — nanocode works on the directory you run it from, so start it inside the project you want it to read and edit:

```bash
cd ~/my-project
java -jar /path/to/nanocode/target/nanocode.jar
```

## Providers

The provider is chosen with the `--provider=` flag; the default is `antigravity`:

| Provider | Auth | Default model |
|----------|------|---------------|
| `antigravity` (default) | Login with Google (browser) | `gemini-3.7-flash-medium` |
| `gemini` | `GEMINI_API_KEY` | `gemini-flash-latest` |
| `anthropic` | `ANTHROPIC_API_KEY` | `claude-opus-4-5` |
| `openrouter` | `OPENROUTER_API_KEY` | `anthropic/claude-opus-4.5` |

Set `MODEL` to override the default model for any provider.

### Antigravity (default)

Google's successor to the gemini-cli individual tier — sign in with a Google account, no API key
or GCP project. A Google AI Pro subscription's credits are applied automatically. Antigravity
model IDs carry an effort suffix, e.g. `gemini-3.7-flash-low`/`-medium`/`-high`,
`gemini-3.6-flash-medium`, `gemini-pro-agent` (Gemini 3.1 Pro):

```bash
export MODEL="gemini-pro-agent"
java -jar target/nanocode.jar
```

The wire protocol mirrors the closed-source `agy` CLI (constants via the open-source
[CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) reimplementation), so it is unofficial
and may change.

### Anthropic

```bash
export ANTHROPIC_API_KEY="your-key"
java -jar target/nanocode.jar --provider=anthropic
```

### Gemini

Use Google's Gemini Interactions API. The default Gemini model is `gemini-flash-latest`.

```bash
export GEMINI_API_KEY="your-key"
java -jar target/nanocode.jar --provider=gemini
```

To use a different model:

```bash
export GEMINI_API_KEY="your-key"
export MODEL="gemini-pro-latest"
java -jar target/nanocode.jar --provider=gemini
```

### OpenRouter

Use [OpenRouter](https://openrouter.ai) to access any model:

```bash
export OPENROUTER_API_KEY="your-key"
java -jar target/nanocode.jar --provider=openrouter
```

To use a different model:

```bash
export OPENROUTER_API_KEY="your-key"
export MODEL="openai/gpt-5.2"
java -jar target/nanocode.jar --provider=openrouter
```

## Notes

- **Windows**: the `bash` tool runs commands through `cmd.exe /c`; everything else works the same.
  Use Windows Terminal for proper colors.
- **Corporate proxies**: if your network TLS-intercepts Google traffic, give the JVM a truststore
  containing your proxy's CA:
  `java -Djavax.net.ssl.trustStore=/path/to/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit -jar target/nanocode.jar`

## Commands

- `/c` - Clear conversation
- `/q` or `exit` - Quit

## Tools

| Tool | Description |
|------|-------------|
| `read` | Read file with line numbers, offset/limit |
| `write` | Write content to file |
| `edit` | Replace string in file (must be unique) |
| `glob` | Find files by pattern, sorted by mtime |
| `grep` | Search files for regex |
| `bash` | Run shell command |

## Example

```
────────────────────────────────────────
❯ what files are here?
────────────────────────────────────────

⏺ Glob(**/*.java)
  ⎿  src/main/java/Nanocode.java

⏺ There's one Java file: src/main/java/Nanocode.java
```

## License

MIT
