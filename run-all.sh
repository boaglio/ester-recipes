#!/usr/bin/env bash
#
# Runs the whole book (reading, check, translation, PDF) and keeps a record of the run in
# runs/<timestamp>/, so runs can be compared later:
#   process.log      everything the app logged
#   run-info.txt     models, code version, scan counts, duration, exit code, main results
#   check-report.md  the pages to review, if the run got that far
#
# Usage:   ./run-all.sh [extra app flags]
# Book:    ./run-all.sh --ester.reviewed.enabled=true   builds the book only from reviewed/,
#          reading no scan, so Ollama is not needed
# Check:   ./run-all.sh --check [extra app flags]   only checks that everything is ready
# Models:  MAIN_MODEL=... CHECK_MODEL=... ./run-all.sh
#
# Run it again after a stop: scans already read are skipped.

set -u
cd "$(dirname "$0")" || exit 1

MAIN_MODEL="${MAIN_MODEL:-qwen2.5vl:7b}"
CHECK_MODEL="${CHECK_MODEL:-gemma4:latest}"
JAR=target/ester-recipes-1.0.0.jar

count_scans() {
    find "$1" -maxdepth 1 -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) 2>/dev/null | wc -l
}

# The main model read the sample scans with about this much free; less was not tested.
MIN_FREE_GPU_MIB=5000

problems=0
ok()      { echo "  ok       $*"; }
warning() { echo "  warning  $*"; }
problem() { echo "  PROBLEM  $*"; problems=$((problems + 1)); }

# Whether an argument starting with $1 was given.
flag_given() {
    local prefix=$1 arg
    shift
    for arg in "$@"; do
        case "$arg" in
            "$prefix"*) return 0 ;;
        esac
    done
    return 1
}

# The value the app will use: its --key=value flag if given, else the default in application.yml.
images_setting() {
    local key=$1 flag_key=$2 arg
    shift 2
    for arg in "$@"; do
        case "$arg" in
            --ester.images."$key"=* | --ester.images."$flag_key"=*)
                echo "${arg#*=}"
                return
                ;;
        esac
    done
    awk -v key="$key" '
        /^  images:/ { inside = 1; next }
        inside && /^ {0,3}[^ #]/ { inside = 0 }
        inside && $1 == key ":" { sub(/^[^:]*:[ ]*/, ""); gsub(/"/, ""); print; exit }
    ' src/main/resources/application.yml 2>/dev/null
}

check_comfyui() {
    local url checkpoint available
    url=$(images_setting base-url baseUrl "$@")
    url=${url%/}
    checkpoint=$(images_setting checkpoint checkpoint "$@")

    if [ -z "$url" ] || [ -z "$checkpoint" ]; then
        problem "couldn't find the ComfyUI address or checkpoint name; set --ester.images.base-url and --ester.images.checkpoint"
        return
    fi

    if curl -fs -m 5 -o /dev/null "$url/system_stats"; then
        ok "ComfyUI is answering on $url"
        # ComfyUI itself is asked, because it may be on another machine.
        available=$(curl -fs -m 10 "$url/object_info/CheckpointLoaderSimple")
        if [ -z "$available" ]; then
            warning "couldn't ask ComfyUI on $url for its checkpoints, so $checkpoint isn't verified"
        elif grep -qF "\"$checkpoint\"" <<<"$available"; then
            ok "ComfyUI has checkpoint $checkpoint"
        else
            problem "ComfyUI on $url doesn't have checkpoint $checkpoint; add it, or set --ester.images.checkpoint"
        fi
        return
    fi

    case "$url" in
        http://localhost:* | http://127.0.0.1:*)
            problem "ComfyUI is not answering on $url; run: docker compose up -d"
            if [ -z "$(find comfyui/models/checkpoints -type f -name "$checkpoint" 2>/dev/null | head -1)" ]; then
                problem "checkpoint $checkpoint is not in comfyui/models/checkpoints/; add it, or set --ester.images.checkpoint"
            fi
            ;;
        *)
            problem "ComfyUI is not answering on $url"
            ;;
    esac
}

preflight() {
    echo "Checking what the run needs:"

    local java_major
    java_major=$(java -version 2>&1 | grep -m1 ' version ' | sed -E 's/.*version "([0-9]+).*/\1/')
    if [ -z "$java_major" ]; then
        problem "Java is not installed; it needs 21 or newer"
    elif [ "$java_major" -lt 21 ]; then
        problem "Java $java_major is too old; it needs 21 or newer"
    else
        ok "Java $java_major"
    fi

    if [ ! -f "$JAR" ]; then
        if command -v mvn >/dev/null; then
            ok "no jar yet; Maven will build it"
        else
            problem "no $JAR, and Maven is not installed to build it"
        fi
    elif [ -n "$(find src pom.xml -type f -newer "$JAR" 2>/dev/null | head -1)" ]; then
        warning "the code changed after the jar was built, and this run uses the old jar; run: mvn package"
    else
        ok "$JAR"
    fi

    if flag_given --ester.reviewed.enabled=true "$@"; then
        local reviewed
        reviewed=$(find reviewed -maxdepth 1 -name '*.json' 2>/dev/null | wc -l)
        if [ "$reviewed" -gt 0 ]; then
            ok "$reviewed reviewed recipe(s) in reviewed/; no scan is read"
        else
            problem "no reviewed recipes in reviewed/; copy them there first (see README)"
        fi
        check_images "$@"
        finish_preflight
        return
    fi

    local models model
    if models=$(ollama list 2>/dev/null); then
        ok "Ollama is answering"
        for model in "$MAIN_MODEL" $(flag_given --ester.check.enabled=false "$@" || echo "$CHECK_MODEL"); do
            if awk '{print $1}' <<<"$models" | grep -qxF "$model"; then
                ok "model $model"
            else
                problem "model $model is missing; run: ollama pull $model"
            fi
        done
    else
        problem "Ollama is not answering; start it, or install it from https://ollama.com"
    fi
    if [ "$MAIN_MODEL" = "$CHECK_MODEL" ] && ! flag_given --ester.check.enabled=false "$@"; then
        problem "the main and check models are both $MAIN_MODEL; the check needs a different one"
    fi

    local pending others stored
    pending=$(count_scans recipes/pending)
    stored=$(find recipes/json -maxdepth 1 -name '*.json' 2>/dev/null | wc -l)
    if [ "$pending" -gt 0 ]; then
        ok "$pending scan(s) in recipes/pending"
    elif [ "$stored" -gt 0 ]; then
        warning "no scans in recipes/pending; the book is built from the $stored recipe(s) stored"
    else
        problem "no scans in recipes/pending and no stored recipes"
    fi
    others=$(find recipes/pending -maxdepth 1 -type f \
        ! -iname '*.jpg' ! -iname '*.jpeg' ! -iname '*.png' 2>/dev/null | wc -l)
    if [ "$others" -gt 0 ]; then
        warning "$others file(s) in recipes/pending will be ignored: only .jpg, .jpeg and .png are read"
    fi

    local free_gpu
    if [ "$pending" -gt 0 ]; then
        free_gpu=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits 2>/dev/null | head -1)
        if [ -z "$free_gpu" ]; then
            warning "couldn't read the free GPU memory"
        elif [ "$free_gpu" -lt "$MIN_FREE_GPU_MIB" ]; then
            warning "only $free_gpu MiB of GPU memory free; close other apps, as reading may fail below $MIN_FREE_GPU_MIB"
        else
            ok "$free_gpu MiB of GPU memory free"
        fi
    fi

    check_images "$@"
    finish_preflight
}

check_images() {
    case " $* " in
        *" --ester.images.enabled=true "*) check_comfyui "$@" ;;
        *) warning "images are off, so recipes with no image yet stay without one; add --ester.images.enabled=true" ;;
    esac
}

finish_preflight() {
    if [ "$problems" -gt 0 ]; then
        echo "Not ready: $problems problem(s) to fix."
        return 1
    fi
    echo "Ready."
}

check_only=false
if [ "${1:-}" = "--check" ]; then
    check_only=true
    shift
fi

# The app refuses or merges a repeated flag, so one given here replaces the script's own.
for arg in "$@"; do
    case "$arg" in
        --spring.ai.ollama.chat.model=*) MAIN_MODEL=${arg#*=} ;;
        --ester.check.model=*) CHECK_MODEL=${arg#*=} ;;
    esac
done
app_flags=()
flag_given --spring.ai.ollama.chat.model= "$@" || app_flags+=("--spring.ai.ollama.chat.model=$MAIN_MODEL")
flag_given --ester.check.model= "$@" || app_flags+=("--ester.check.model=$CHECK_MODEL")
flag_given --ester.check.enabled= "$@" || app_flags+=(--ester.check.enabled=true)
flag_given --ester.translation.enabled= "$@" || app_flags+=(--ester.translation.enabled=true)

preflight "$@" || exit 1
if "$check_only"; then
    exit 0
fi

if [ ! -f "$JAR" ]; then
    mvn -q package -DskipTests || exit 1
fi

timestamp=$(date +%Y-%m-%d-%H_%M_%S)
run_dir="runs/$timestamp"
mkdir -p "$run_dir" output
info="$run_dir/run-info.txt"
started=$(date +%s)
marker="$run_dir/.started"
touch "$marker"

{
    echo "started:          $(date '+%Y-%m-%d %H:%M:%S')"
    echo "main model:       $MAIN_MODEL"
    echo "check model:      $CHECK_MODEL"
    echo "extra flags:      $*"
    echo "code version:     $(git rev-parse --short HEAD 2>/dev/null || echo unknown)," \
        "$(git status --porcelain 2>/dev/null | wc -l) file(s) changed or new"
    echo "jar built:        $(date -r "$JAR" '+%Y-%m-%d %H:%M:%S')"
    echo "ollama:           $(ollama --version 2>/dev/null | grep -oE '[0-9]+(\.[0-9]+)+' | tail -1)"
    echo "scans pending:    $(count_scans recipes/pending)"
    echo "scans processed:  $(count_scans recipes/processed)"
} >"$info"

# Completes the record however the run ends, including Ctrl-C.
status=interrupted
finish() {
    trap - EXIT INT TERM
    report=$(find output -maxdepth 1 -name 'check-report-*.md' -newer "$marker" 2>/dev/null | sort | tail -1)
    if [ -n "$report" ]; then
        cp "$report" "$run_dir/check-report.md"
    fi
    rm -f "$marker"
    {
        echo "scans left:       $(count_scans recipes/pending)"
        echo "scans no recipe:  $(count_scans recipes/no-recipe)"
        echo "duration:         $(( ($(date +%s) - started) / 60 )) min"
        echo "exit code:        $status"
        echo
        echo "results:"
        grep -E 'Check phase done|Translation phase done|could NOT be processed|Done\. ' \
            "$run_dir/process.log" | sed -E 's/^.* : //'
    } >>"$info"
    echo "Run saved in $run_dir (exit code $status)"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "Saving this run in $run_dir"
java -jar "$JAR" "${app_flags[@]}" "$@" 2>&1 | tee "$run_dir/process.log"
status=${PIPESTATUS[0]}
exit "$status"
