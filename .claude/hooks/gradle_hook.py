#!/usr/bin/env python3
"""Claude Code 훅 래퍼 (D2 §5).

Gradle 종료 상태를 훅 종료 코드로 바꾸고, 출력 크기를 제한하고, Stop 반복을 한 번으로 묶는다.
검사 로직은 넣지 않는다. 검사는 Gradle `archCheck`가 한다.
"""
import hashlib
import json
import os
import signal
import subprocess
import sys
import tempfile
import time

FAST = (["./gradlew", "archCheck", "-ParchFast=true"], 110)
FULL = (["./gradlew", "build", "archCheck"], 290)
TAIL_BYTES = 12000
SCOPE_DIRS = ("src/", "gradle/")
SCOPE_FILES = ("build.gradle.kts", "settings.gradle.kts", "gradle.properties")
SCOPE_PATHS = ["src", "gradle", *SCOPE_FILES]
LOG_DIR = os.path.join("build", "hook-logs")
FINGERPRINT = os.path.join(LOG_DIR, "last-success")
GIVE_UP = "검증 실패가 계속되어 자동 반복을 중단했다. 작업 완료가 아니며 로그 확인과 사람 개입이 필요하다."


def emit(message):
    print(json.dumps({"systemMessage": message}, ensure_ascii=False))


def block(message):
    sys.stderr.write(message.rstrip("\n") + "\n")
    sys.exit(2)


def in_scope(project_dir, file_path):
    """범위 안이면 True, 범위 밖이면 False, 해석하지 못하면 None."""
    if not isinstance(file_path, str) or not file_path:
        return None
    normalized = file_path.replace("\\", "/")
    root = project_dir.replace("\\", "/").rstrip("/") + "/"
    try:
        real_file = os.path.realpath(normalized).replace("\\", "/")
        real_root = os.path.realpath(root).replace("\\", "/").rstrip("/") + "/"
    except (OSError, ValueError):
        return None
    if real_file.startswith(real_root):
        rel = real_file[len(real_root):]
    elif normalized.startswith(root):
        rel = normalized[len(root):]
    else:
        return False
    return rel.startswith(SCOPE_DIRS) or rel in SCOPE_FILES


def git(project_dir, *args):
    try:
        done = subprocess.run(["git", *args], cwd=project_dir, capture_output=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return done.stdout if done.returncode == 0 else None


def fingerprint(project_dir):
    """HEAD, 범위 안 변경 목록, 변경 파일 내용의 SHA-256을 합친다. git이 없으면 None."""
    head = git(project_dir, "rev-parse", "--verify", "-q", "HEAD") or b"no-head"
    status = git(project_dir, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", *SCOPE_PATHS)
    if status is None:
        return None
    digest = hashlib.sha256()
    digest.update(head.strip() + b"\0" + status)
    entries = [e for e in status.split(b"\0") if e]
    for entry in sorted(entries):
        path = entry[3:].decode("utf-8", "surrogateescape")
        full = os.path.join(project_dir, path)
        digest.update(entry + b"\0")
        if os.path.isfile(full):
            with open(full, "rb") as handle:
                digest.update(hashlib.sha256(handle.read()).digest())
    return digest.hexdigest()


def read_text(path):
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return None


def run(project_dir, command, limit):
    """명령을 실행하고 (종료 상태 또는 None(timeout·실행 불가), 로그 경로, 경과 초, 설명)을 돌려준다."""
    log_dir = os.path.join(project_dir, LOG_DIR)
    os.makedirs(log_dir, exist_ok=True)
    fd, log_path = tempfile.mkstemp(prefix="hook-", suffix=".log", dir=log_dir)
    started = time.monotonic()
    with os.fdopen(fd, "wb") as log:
        try:
            proc = subprocess.Popen(
                command, cwd=project_dir, stdin=subprocess.DEVNULL,
                stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
            )
        except OSError as error:
            log.write(("실행 불가: %s\n" % error).encode("utf-8"))
            return None, log_path, time.monotonic() - started, "실행 불가"
        try:
            code = proc.wait(timeout=limit)
            return code, log_path, time.monotonic() - started, "종료 상태 %d" % code
        except subprocess.TimeoutExpired:
            kill_group(proc)
            log.write(("\n제한 시간 %d초 초과로 종료했다.\n" % limit).encode("utf-8"))
            return None, log_path, time.monotonic() - started, "제한 시간 %d초 초과" % limit


def kill_group(proc):
    for sig, wait in ((signal.SIGTERM, 5), (signal.SIGKILL, 5)):
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            return
        try:
            proc.wait(timeout=wait)
            return
        except subprocess.TimeoutExpired:
            continue


def tail(log_path):
    """로그 끝 TAIL_BYTES 안의 완전한 줄만 돌려준다. (본문, 생략 여부)"""
    size = os.path.getsize(log_path)
    with open(log_path, "rb") as handle:
        handle.seek(max(0, size - TAIL_BYTES))
        data = handle.read()
    truncated = size > TAIL_BYTES
    if truncated:
        newline = data.find(b"\n")
        data = data[newline + 1:] if newline >= 0 else b""
    return data.decode("utf-8", "replace"), truncated


def failure_text(command, detail, log_path):
    body, truncated = tail(log_path)
    lines = []
    if body.strip():
        lines.append(body.rstrip("\n"))
    else:
        lines.append("출력 끝의 한 줄이 %d바이트보다 길어 여기 싣지 않았다. 전체 로그를 확인한다." % TAIL_BYTES)
    lines.append("---")
    lines.append("명령: %s" % " ".join(command))
    lines.append("결과: %s" % detail)
    if truncated:
        lines.append("앞부분 출력은 생략했다. 마지막 %d바이트 안의 완전한 줄만 실었다." % TAIL_BYTES)
    lines.append("전체 로그: %s" % log_path)
    return "\n".join(lines)


def post_tool_use(project_dir, payload):
    tool_input = payload.get("tool_input") or {}
    if in_scope(project_dir, tool_input.get("file_path")) is False:
        return 0
    command, limit = FAST
    code, log_path, elapsed, detail = run(project_dir, command, limit)
    if code == 0:
        os.remove(log_path)
        emit("빠른 구조 검사 성공: %s (%.1f초)" % (" ".join(command), elapsed))
        return 0
    block(failure_text(command, detail, log_path))


def stop(project_dir, payload):
    retry = bool(payload.get("stop_hook_active"))
    marker = os.path.join(project_dir, FINGERPRINT)
    current = fingerprint(project_dir)
    if current is not None and current == read_text(marker):
        emit("마지막 성공 뒤 검사 범위가 바뀌지 않아 전체 검사를 생략했다. 생략은 검증 증거가 아니다.")
        return 0
    command, limit = FULL
    code, log_path, elapsed, detail = run(project_dir, command, limit)
    if code == 0:
        os.remove(log_path)
        if current is not None:
            with open(marker, "w", encoding="utf-8") as handle:
                handle.write(current + "\n")
        emit("전체 검사 성공: %s (%.1f초)" % (" ".join(command), elapsed))
        return 0
    if os.path.exists(marker):
        os.remove(marker)
    if retry:
        emit("%s 명령: %s, 결과: %s, 전체 로그: %s" % (GIVE_UP, " ".join(command), detail, log_path))
        return 0
    block(failure_text(command, detail, log_path))


def main():
    project_dir = os.environ.get("CLAUDE_PROJECT_DIR")
    if not project_dir or not os.path.isdir(project_dir):
        block("훅 실행 실패: CLAUDE_PROJECT_DIR가 없거나 디렉터리가 아니다.")
    try:
        payload = json.load(sys.stdin)
    except (ValueError, OSError) as error:
        block("훅 실행 실패: 입력 JSON을 읽지 못했다 (%s)." % error)
    if not isinstance(payload, dict):
        block("훅 실행 실패: 입력 JSON이 객체가 아니다.")
    event = payload.get("hook_event_name")
    if event == "PostToolUse":
        return post_tool_use(project_dir, payload)
    if event == "Stop":
        return stop(project_dir, payload)
    block("훅 실행 실패: 허용하지 않은 이벤트 %r." % event)


if __name__ == "__main__":
    sys.exit(main())
