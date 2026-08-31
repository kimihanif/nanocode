# nanocode

Minimal Claude Code alternative. Single Java file, built with Maven, zero..eh..1 json dependency, ~330 lines.

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

```bash
mvn -q package
export GEMINI_API_KEY="your-key"   # or ANTHROPIC_API_KEY / OPENROUTER_API_KEY
java -jar target/nanocode.jar
```

Then type what you want done at the `❯` prompt — nanocode works on the directory you run it from, so start it inside the project you want it to read and edit:

```bash
cd ~/my-project
java -jar /path/to/nanocode/target/nanocode.jar
```

## Providers

The provider is picked from whichever API key is set, in this order:

| Priority | Env var | Provider | Default model |
|----------|---------|----------|---------------|
| 1 | `OPENROUTER_API_KEY` | OpenRouter | `anthropic/claude-opus-4.5` |
| 2 | `GEMINI_API_KEY` | Gemini | `gemini-flash-latest` |
| 3 | `ANTHROPIC_API_KEY` | Anthropic | `claude-opus-4-5` |

Set `MODEL` to override the default model for any provider.

### Anthropic

```bash
export ANTHROPIC_API_KEY="your-key"
java -jar target/nanocode.jar
```

### Gemini

Use Google's Gemini Interactions API. The default Gemini model is `gemini-flash-latest`.

```bash
export GEMINI_API_KEY="your-key"
java -jar target/nanocode.jar
```

To use a different model:

```bash
export GEMINI_API_KEY="your-key"
export MODEL="gemini-pro-latest"
java -jar target/nanocode.jar
```

### OpenRouter

Use [OpenRouter](https://openrouter.ai) to access any model:

```bash
export OPENROUTER_API_KEY="your-key"
java -jar target/nanocode.jar
```

To use a different model:

```bash
export OPENROUTER_API_KEY="your-key"
export MODEL="openai/gpt-5.2"
java -jar target/nanocode.jar
```

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
