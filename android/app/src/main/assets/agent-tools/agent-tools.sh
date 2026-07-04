#!/bin/bash
# agent-tools.sh — Terminal-based AI agent tools for CodeSpace IDE
# Source in .bashrc or run directly: ./agent-tools.sh <command> [args]
#
# Gives ANY AI in the terminal (Claude Code, Ollama, etc.) the same
# capabilities as the app's Kotlin AgentTools:
#   git, remotion, secrets, web, memory, connectors, scheduling, data

AGENT_MEMORY_DIR="${HOME}/.agent-memory"
AGENT_SECRET_DIR="${HOME}/.agent-secrets"
AGENT_DATA_DIR="${HOME}/.agent-data"
AGENT_CRON_FILE="${HOME}/.agent-crontab"

mkdir -p "$AGENT_MEMORY_DIR" "$AGENT_SECRET_DIR" "$AGENT_DATA_DIR" 2>/dev/null

# ── Git ───────────────────────────────────────────────────────────────
git-pull-rebase() {
    local dir="${1:-.}"
    cd "$dir" && git pull --rebase && git push
}

git-commit-push() {
    local message="$1"; local dir="${2:-.}"
    cd "$dir" && git add -A && git commit -m "$message" && git push
}

git-branch-create() {
    local name="$1"; local dir="${2:-.}"
    cd "$dir" && git checkout -b "$name"
}

git-status-short() {
    local dir="${1:-.}"; cd "$dir" && git status --short
}

git-diff-staged() {
    local dir="${1:-.}"; cd "$dir" && git diff --cached
}

# ── Remotion ──────────────────────────────────────────────────────────
install-remotion() {
    echo "Installing Remotion CLI and FFmpeg..."
    npm install -g @remotion/cli 2>/dev/null || npm install remotion 2>/dev/null
    apt install -y ffmpeg 2>/dev/null || echo "FFmpeg: run 'apt install ffmpeg' in proot"
    echo "Done."
}

render-remotion() {
    local composition="$1"; local output="$2"; local project_dir="${3:-.}"
    local temp_dir="${project_dir}/.remotion_tmp_$(date +%s)"
    mkdir -p "$temp_dir"
    echo "Rendering $composition..."
    cd "$project_dir"
    npx remotion render "$composition" "$temp_dir/clip.mp4" --concurrency=1
    if [ -f "$temp_dir/clip.mp4" ]; then
        mkdir -p "$(dirname "$output")"
        cp "$temp_dir/clip.mp4" "$output"
        find "$temp_dir" -delete 2>/dev/null
        echo "Video rendered: $output ($(du -h "$output" | cut -f1))"
    else
        echo "Render failed - no output"
        find "$temp_dir" -delete 2>/dev/null
        return 1
    fi
}

# ── Secrets ───────────────────────────────────────────────────────────
detect-secrets() {
    local target="$1"; local content=""
    if [ -f "$target" ]; then content=$(cat "$target"); else content="$target"; fi
    echo "Scanning for secrets..."
    local found=0
    echo "$content" | grep -oP 'AKIA[0-9A-Z]{16}' | while read m; do echo "AWS Key: ${m:0:4}...${m: -4}"; found=1; done
    echo "$content" | grep -oP 'gh[pousr]_[A-Za-z0-9]{36,}' | while read m; do echo "GitHub Token: ${m:0:4}...${m: -4}"; found=1; done
    echo "$content" | grep -oP 'AIza[0-9A-Za-z_\-]{35}' | while read m; do echo "Google Key: ${m:0:4}...${m: -4}"; found=1; done
    echo "$content" | grep -oP 'sk-[A-Za-z0-9]{48}' | while read m; do echo "OpenAI Key: ${m:0:4}...${m: -4}"; found=1; done
    echo "$content" | grep -oP 'xox[baprs]-[A-Za-z0-9-]+' | while read m; do echo "Slack Token: ${m:0:4}...${m: -4}"; found=1; done
    echo "$content" | grep -q '-----BEGIN.*PRIVATE KEY-----' && { echo "Private Key block detected"; found=1; }
    echo "$content" | grep -oP 'eyJ[A-Za-z0-9_\-]+\.eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_]+' | while read m; do echo "JWT: ${m:0:4}...${m: -4}"; found=1; done
    echo "$content" | grep -iP '(api[_-]?key|secret[_-]?key|auth[_-]?token)\s*[=:]\s*["'"'"']?[A-Za-z0-9_\-]{20,}' | while read m; do echo "Generic key: $(echo $m | cut -c1-30)..."; found=1; done
    [ "$found" = "0" ] && echo "No secrets detected."
}

save-secret() {
    local key="$1"; local value="$2"
    echo "$value" > "$AGENT_SECRET_DIR/$key"; chmod 600 "$AGENT_SECRET_DIR/$key"
    echo "Secret '$key' saved."
}

get-secret() {
    local key="$1"
    [ -f "$AGENT_SECRET_DIR/$key" ] && cat "$AGENT_SECRET_DIR/$key" || echo "No secret for '$key'"
}

# ── Web ───────────────────────────────────────────────────────────────
web-search() {
    local q=$(echo "$1" | sed 's/ /+/g')
    curl -s "https://html.duckduckgo.com/html/?q=$q" | grep -oP '<a[^>]*class="result__a"[^>]*>(.*?)</a>' | sed 's/<[^>]*>//g' | head -5
}

web-fetch() {
    curl -sL "$1" | head -200
}

# ── Memory ────────────────────────────────────────────────────────────
save-memory() {
    echo "$2" > "$AGENT_MEMORY_DIR/$1"; echo "Memory saved: '$1'"
}

read-memory() {
    if [ -d "$AGENT_MEMORY_DIR" ] && [ "$(ls -A "$AGENT_MEMORY_DIR" 2>/dev/null)" ]; then
        echo "Memories:"; for f in "$AGENT_MEMORY_DIR"/*; do echo "  $(basename $f): $(head -c 200 $f)"; done
    else echo "No memories saved."; fi
}

delete-memory() {
    [ -f "$AGENT_MEMORY_DIR/$1" ] && { rm "$AGENT_MEMORY_DIR/$1"; echo "Deleted: '$1'"; } || echo "No memory for '$1'"
}

# ── Connectors ────────────────────────────────────────────────────────
list-connectors() {
    echo "Available: gmail, gcalendar, gdrive, slack, github"
    echo "Connected:"; ls "$AGENT_SECRET_DIR"/connector_*_token 2>/dev/null | sed 's/.*connector_//;s/_token//' || echo "  (none)"
}

connect-service() {
    case "$1" in
        gmail)     echo "Auth URL: https://accounts.google.com/o/oauth2/v2/auth?client_id=YOUR_ID&redirect_uri=urn:ietf:wg:oauth:2.0:oob&response_type=code&scope=https://www.googleapis.com/auth/gmail" ;;
        gcalendar) echo "Auth URL: https://accounts.google.com/o/oauth2/v2/auth?client_id=YOUR_ID&redirect_uri=urn:ietf:wg:oauth:2.0:oob&response_type=code&scope=https://www.googleapis.com/auth/calendar" ;;
        gdrive)    echo "Auth URL: https://accounts.google.com/o/oauth2/v2/auth?client_id=YOUR_ID&redirect_uri=urn:ietf:wg:oauth:2.0:oob&response_type=code&scope=https://www.googleapis.com/auth/drive" ;;
        slack)     echo "Auth URL: https://slack.com/oauth/v2/authorize?client_id=YOUR_ID&scope=chat:write" ;;
        github)    echo "Auth URL: https://github.com/login/oauth/authorize?client_id=YOUR_ID&scope=repo" ;;
        *) echo "Unknown: $1. Available: gmail, gcalendar, gdrive, slack, github" ;;
    esac
    echo "After auth: save-secret connector_${1}_token YOUR_TOKEN"
}

use-connector() {
    local svc="$1"; local method="$2"; local ep="$3"
    local token=$(get-secret "connector_${svc}_token")
    case "$svc" in
        gmail)     curl -s -X "$method" -H "Authorization: Bearer $token" "https://gmail.googleapis.com/gmail/v1$ep" | head -100 ;;
        gcalendar) curl -s -X "$method" -H "Authorization: Bearer $token" "https://www.googleapis.com/calendar/v3$ep" | head -100 ;;
        gdrive)    curl -s -X "$method" -H "Authorization: Bearer $token" "https://www.googleapis.com/drive/v3$ep" | head -100 ;;
        slack)     curl -s -X "$method" -H "Authorization: Bearer $token" "https://slack.com/api$ep" | head -100 ;;
        github)    curl -s -X "$method" -H "Authorization: token $token" "https://api.github.com$ep" | head -100 ;;
        *) echo "Unknown service: $svc" ;;
    esac
}

# ── Scheduling ────────────────────────────────────────────────────────
schedule-task() {
    echo "$2 $3 # $1" >> "$AGENT_CRON_FILE"
    crontab "$AGENT_CRON_FILE" 2>/dev/null
    echo "Task '$1' scheduled."
}

list-tasks() {
    [ -f "$AGENT_CRON_FILE" ] && cat "$AGENT_CRON_FILE" || echo "No scheduled tasks."
}

cancel-task() {
    [ -f "$AGENT_CRON_FILE" ] && { grep -v "# $1$" "$AGENT_CRON_FILE" > "$AGENT_CRON_FILE.tmp"; mv "$AGENT_CRON_FILE.tmp" "$AGENT_CRON_FILE"; crontab "$AGENT_CRON_FILE" 2>/dev/null; echo "Cancelled: '$1'"; } || echo "No task '$1'"
}

# ── Data ──────────────────────────────────────────────────────────────
create-entity() {
    local id=$(date +%s); mkdir -p "$AGENT_DATA_DIR/$1"
    echo "$2" | python3 -c "import sys,json;d=json.load(sys.stdin);d['id']=$id;print(json.dumps(d,indent=2))" > "$AGENT_DATA_DIR/$1/$id.json" 2>/dev/null || echo "$2" > "$AGENT_DATA_DIR/$1/$id.json"
    echo "Created $1: $id"
}

read-entities() {
    [ -d "$AGENT_DATA_DIR/$1" ] && cat "$AGENT_DATA_DIR/$1"/*.json 2>/dev/null | head -50 || echo "No $1 records."
}

# ── Packages ──────────────────────────────────────────────────────────
install-package() {
    case "$1" in
        npm) npm install "$2" ;;
        pip) pip3 install "$2" ;;
        apt) apt install -y "$2" ;;
        *) echo "Use: npm, pip, or apt" ;;
    esac
}

# ── Dispatch ──────────────────────────────────────────────────────────
case "${1:-}" in
    git-pull-rebase)   shift; git-pull-rebase "$@" ;;
    git-commit-push)   shift; git-commit-push "$@" ;;
    git-branch-create) shift; git-branch-create "$@" ;;
    git-status)        shift; git-status-short "$@" ;;
    git-diff)          shift; git-diff-staged "$@" ;;
    render-remotion)   shift; render-remotion "$@" ;;
    install-remotion)  install-remotion ;;
    detect-secrets)    shift; detect-secrets "$@" ;;
    save-secret)       shift; save-secret "$@" ;;
    get-secret)        shift; get-secret "$@" ;;
    web-search)        shift; web-search "$@" ;;
    web-fetch)         shift; web-fetch "$@" ;;
    save-memory)       shift; save-memory "$@" ;;
    read-memory)       read-memory ;;
    delete-memory)     shift; delete-memory "$@" ;;
    list-connectors)   list-connectors ;;
    connect-service)   shift; connect-service "$@" ;;
    use-connector)     shift; use-connector "$@" ;;
    schedule-task)     shift; schedule-task "$@" ;;
    list-tasks)        list-tasks ;;
    cancel-task)       shift; cancel-task "$@" ;;
    create-entity)     shift; create-entity "$@" ;;
    read-entities)     shift; read-entities "$@" ;;
    install-package)   shift; install-package "$@" ;;
    *) echo "CodeSpace Agent Tools - 24 commands available"
       echo "Run: agent-tools.sh <command> [args]"
       echo "Commands: git-pull-rebase, git-commit-push, git-branch-create, git-status,"
       echo "  git-diff, render-remotion, install-remotion, detect-secrets, save-secret,"
       echo "  get-secret, web-search, web-fetch, save-memory, read-memory, delete-memory,"
       echo "  list-connectors, connect-service, use-connector, schedule-task, list-tasks,"
       echo "  cancel-task, create-entity, read-entities, install-package" ;;
esac
