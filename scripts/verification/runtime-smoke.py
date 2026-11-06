"""실제 Compose 구조·명세·인증·독립 실행 검사. 비밀 파일을 열거나 토큰·값을 출력하지 않는다."""
import argparse
import json
import re
import socket
import sys
import subprocess
import urllib.error
import urllib.request
import uuid
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--independence", action="store_true", help="앱·DB를 잠시 단독 모드로 전환 후 복구")
args = parser.parse_args()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
ORIGIN = "http://127.0.0.1:8081"
TOKEN = None


def docker(*arguments):
    result = subprocess.run(["docker", "compose", *arguments], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=180)
    if result.returncode:
        raise RuntimeError("Compose 명령 실패: " + " ".join(arguments[:4]))
    return result.stdout


def send(path, method="GET", auth=True, headers=None):
    """상태·헤더·본문을 돌려준다. 토큰은 헤더로만 보내고 출력하지 않는다."""
    request_headers = dict(headers or {})
    if auth is True and TOKEN:
        request_headers["Authorization"] = "Bearer " + TOKEN
    elif isinstance(auth, str):
        request_headers["Authorization"] = auth
    data = b"" if method in ("POST", "PUT", "PATCH") else None
    request = urllib.request.Request(ORIGIN + path, data=data, method=method, headers=request_headers)
    try:
        with opener.open(request, timeout=15) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()


def check_http(path, expected, code=None, no_store=False, problem_type=None, method="GET", auth=True):
    status, headers, body = send(path, method, auth)
    if body:
        assert headers.get_content_type() == "application/json", (path, status, "응답 미디어 타입")
    assert status in (expected if isinstance(expected, tuple) else (expected,)), (path, method, status, expected)
    if no_store:
        assert headers.get("Cache-Control") == "no-store", path
    parsed = json.loads(body) if body else None
    if code:
        assert parsed.get("code") == (code[status] if isinstance(code, dict) else code), (path, status, parsed.get("code"))
    if problem_type:
        expected_type = problem_type[status] if isinstance(problem_type, dict) else problem_type
        assert parsed.get("type") == expected_type, (path, status, parsed.get("type"))
    if code or problem_type:
        assert set(parsed) == {"type", "title", "status", "detail", "instance", "code"}, path
    return parsed


def local_token():
    """local 프로필의 Swagger 초기화 스크립트가 넣는 짧은 만료 토큰(POL-SEC-005)."""
    status, headers, body = send("/swagger-ui/swagger-initializer.js", auth=False)
    assert status == 200
    assert headers.get("Cache-Control") == "no-store"
    match = re.match(r"window\.taskSwaggerSettings = (\{.*?\});\n", body.decode("utf-8"))
    assert match, "초기화 스크립트에 local 설정이 없습니다"
    settings = json.loads(match.group(1))
    assert settings["groupProxyEnabled"] is True
    token = settings["accessToken"]
    assert isinstance(token, str) and token.count(".") == 2, "local 토큰 형식이 JWT가 아닙니다"
    assert isinstance(settings["accessTokenExpiresAt"], int)
    return token


NOT_FOUND_MEMBER = "urn:problem-type:member-not-found"
NOT_FOUND_MEMBERSHIP = "urn:problem-type:group-membership-not-found"
GROUP_UNAVAILABLE = "urn:problem-type:group-service-unavailable"
GROUP_TIMEOUT = "urn:problem-type:group-service-timeout"
PROXY_DOWN_CODE = {503: "SERVICE_UNAVAILABLE", 504: "GATEWAY_TIMEOUT"}
PROXY_DOWN_TYPE = {503: GROUP_UNAVAILABLE, 504: GROUP_TIMEOUT}


def check_members():
    """고정 local 시드의 업무 API. 실제 개인정보나 본문을 출력하지 않는다."""
    expected = ["tu3fgxd45k", "dj8fy4bewk", "k2m9x0p1q3", "j0n8k2m4p6", "q1w2e3r4t5"]
    page = check_http("/api/members", 200)
    assert [m["id"] for m in page["members"]] == expected, "local 시드 목록과 다릅니다. 기존 데이터는 수정하지 않습니다"
    assert page["page"] == {"number": 1, "size": 20, "totalElements": 5, "totalPages": 1}
    assert isinstance(page["members"][0]["group"], str)
    assert page["members"][0]["permission"] == "Editor"
    assert page["members"][2]["group"] is None and page["members"][2]["permission"] is None
    assert all(set(member) == {"id", "name", "group", "permission"} for member in page["members"])
    ids = []
    for number in range(1, 4):
        result = check_http(f"/api/members?page={number}&size=2&sort=name,desc", 200)
        assert result["page"]["number"] == number
        ids.extend(m["id"] for m in result["members"])
    assert ids == ["j0n8k2m4p6", "q1w2e3r4t5", "k2m9x0p1q3", "dj8fy4bewk", "tu3fgxd45k"]
    assert check_http("/api/members?page=10", 200)["members"] == []
    assert check_http("/api/members?size=101", 200)["page"]["size"] == 100
    detail = check_http("/api/member/tu3fgxd45k", 200, no_store=True)
    assert detail["registeredDate"] == "2026-08-15 13:00"
    assert set(detail) == {"id", "name", "email", "address", "registeredDate", "group"}
    assert set(detail["group"]) == {"id", "name", "registeredDate", "pricing", "permission"}
    assert detail["group"]["registeredDate"] == "2026-08-15 13:00"
    assert detail["group"]["pricing"] == "Premium"
    assert check_http("/api/member/k2m9x0p1q3", 200)["group"] is None
    # 형식 위반 400, 형식은 맞지만 처리할 수 없는 값 422(POL-API-002)
    for query in ("page=", "size=%20", "page=-1", "size=2147483648", "page=1&page=1",
                  "size=1&size=1", "sort=name,asc&sort=name,asc", "sort="):
        check_http("/api/members?" + query, 400, "INVALID_PARAMETER")
    for query in ("page=0", "page=21474838&size=100", "sort=id,asc", "size=0"):
        check_http("/api/members?" + query, 422, "VALIDATION_FAILED")
    check_http("/api/member/BAD", 400, "INVALID_PARAMETER")
    check_http("/api/member/missing001", 404, "RESOURCE_NOT_FOUND", problem_type=NOT_FOUND_MEMBER)


def check_security():
    """JWT·scope·GET 전용·보호 자원 메타데이터(ADR-006, POL-API-008)."""
    for auth in (False, "Bearer not-a-jwt", "Basic dGVzdA=="):
        status, headers, body = send("/api/members", auth=auth)
        assert status == 401, (auth is False, status)
        assert headers.get("WWW-Authenticate", "").startswith("Bearer")
        assert "resource_metadata=" in headers.get("WWW-Authenticate", "")
        assert json.loads(body)["code"] == "UNAUTHORIZED"
        assert TOKEN not in body.decode("utf-8")
    check_http("/internal-api/group/api/group/tu3fgxd45k", 401, "UNAUTHORIZED", auth=False)
    metadata = check_http("/.well-known/oauth-protected-resource", 200, auth=False)
    assert metadata["resource_name"] == "member-service"
    assert metadata["scopes_supported"] == ["member.read", "group.read"]
    assert metadata["bearer_methods_supported"] == ["header"]
    for path in ("/api/members", "/api/member/tu3fgxd45k", "/internal-api/group/api/groups/summary?memberIds=tu3fgxd45k",
                 "/v3/api-docs", "/actuator/health/readiness"):
        for method in ("HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE"):
            status, headers, body = send(path, method, auth=False)
            assert status == 405, (path, method, status)
            assert headers.get("Allow") == "GET", (path, method)
            if method != "HEAD":
                assert json.loads(body)["code"] == "METHOD_NOT_ALLOWED", (path, method)
            else:
                assert body == b"", path


model = json.loads(docker("config", "--format", "json"))
services = model["services"]
assert set(services) == {"member-service", "group-service", "member-db", "group-db"}
assert not services["group-service"].get("ports")
assert services["member-service"]["environment"]["TASK_GROUP_MEMBERSHIP_SOURCE"] == "http"
assert set(services["member-service"]["networks"]) == {"member-db-net", "service-internal"}
assert set(services["group-service"]["networks"]) == {"group-db-net", "service-internal"}
assert set(services["member-db"]["networks"]) == {"member-db-net"}
assert set(services["group-db"]["networks"]) == {"group-db-net"}
assert model["networks"]["service-internal"]["internal"]
for service in ("member", "group"):
    assert set(services[service + "-service"]["depends_on"]) == {service + "-db"}

assert check_http("/actuator/health/readiness", 200, auth=False)["status"] == "UP"
TOKEN = local_token()
member_spec = check_http("/v3/api-docs", 200, auth=False)
assert "/internal-docs/group" not in member_spec["paths"]
assert set(member_spec["paths"]) == {"/api/members", "/api/member/{memberId}"}
assert member_spec["security"] == [{"bearerAuth": []}]
for server in member_spec.get("servers", []):
    server["url"] = "/"
assert member_spec == json.loads(Path("services/member-service/docs/openapi.json").read_text(encoding="utf-8")), \
    "실제 Compose OpenAPI가 공유 스냅샷과 다릅니다"
check_members()
print("통과: 실제 Compose 목록·상세·단일 소속·정렬·페이지·400/422 구분·공유 명세 일치", flush=True)
check_security()
print("통과: local Swagger 토큰으로 인증·토큰 없음/무효 401·보호 자원 메타데이터·GET 외 405 + Allow", flush=True)

group_spec = check_http("/internal-docs/group", 200, auth=False)
assert group_spec["info"]["title"] == "그룹 서비스 API"
assert group_spec["servers"] == [{"url": "/internal-api/group"}]
assert group_spec["security"] == [{"bearerAuth": []}]
assert set(group_spec["components"]["securitySchemes"]) == {"bearerAuth"}, "그룹 내부 키 스킴이 노출되었습니다"
expected_group = json.loads(Path("services/group-service/docs/openapi.json").read_text(encoding="utf-8"))
assert set(group_spec["paths"]) == set(expected_group["paths"])
# 중계는 제공자 응답을 공개 계약으로 투영하므로 설명과 오류 상태는 원본과 구별한다.
for name, schema in group_spec["components"]["schemas"].items():
    provider = expected_group["components"]["schemas"][name]
    assert set(schema["properties"]) == set(provider["properties"]), name
    assert set(schema["required"]) == set(provider["required"]), name
    assert schema["additionalProperties"] is False
for path, item in group_spec["paths"].items():
    assert item["get"]["parameters"] == expected_group["paths"][path]["get"]["parameters"]
    assert {"401", "403", "405", "429", "502", "503", "504"} <= set(item["get"]["responses"])
    assert "security" not in item["get"]

summary = check_http("/internal-api/group/api/groups/summary?memberIds=tu3fgxd45k&memberIds=dj8fy4bewk", 200, no_store=True)
assert summary["group"][0]["totalMemberCount"] == 2
assert len(summary["group"][0]["members"]) == 2

# 요청하지 않은 같은 그룹의 멤버나 없는 매핑이 count를 늘리지 않아야 한다.
subset = check_http("/internal-api/group/api/groups/summary?memberIds=tu3fgxd45k&memberIds=missing001", 200)
assert len(subset["group"]) == 1
assert subset["group"][0]["totalMemberCount"] == len(subset["group"][0]["members"]) == 1
assert check_http("/internal-api/group/api/groups/summary?memberIds=missing001&memberIds=missing002", 200)["group"] == []
unassigned = check_http("/internal-api/group/api/groups/summary?memberIds=tu3fgxd45k&memberIds=k2m9x0p1q3", 200)
assert len(unassigned["group"]) == 1
assert unassigned["group"][0]["totalMemberCount"] == len(unassigned["group"][0]["members"]) == 1
assert all(m["permission"] is not None for g in unassigned["group"] for m in g["members"])
assert check_http("/internal-api/group/api/groups/summary?memberIds=k2m9x0p1q3", 200)["group"] == []
detail = check_http("/internal-api/group/api/group/tu3fgxd45k", 200, no_store=True)
assert set(detail) == {"memberId", "groupId", "groupName", "registeredDate", "pricing", "permission"}
assert detail["registeredDate"] == "2026-08-15 13:00"
check_http("/internal-api/group/api/group/k2m9x0p1q3", 404, "RESOURCE_NOT_FOUND", problem_type=NOT_FOUND_MEMBERSHIP)
check_http("/internal-api/group/api/group/BAD", 400, "INVALID_PARAMETER")
check_http("/internal-api/group/api/groups/summary?memberIds=BAD", 400, "INVALID_PARAMETER")
check_http("/internal-api/group/api/groups/summary?memberIds=%7Bbad%7D", 400, "INVALID_PARAMETER")
check_http("/internal-api/group/api/groups/summary?memberIds=tu3fgxd45k,dj8fy4bewk", 400, "INVALID_PARAMETER")
check_http("/internal-api/group/api/groups/summary?" + "&".join(f"memberIds=m{n:09d}" for n in range(101)),
           422, "VALIDATION_FAILED")
check_http("/internal-api/group/actuator/env", 404, "RESOURCE_NOT_FOUND")
# 중복 ID는 응답에서 한 번만 나오고 그룹 로그에 원문이 남는다(사용자 결정, REVIEW-002).
dedupe_id = "verify-" + uuid.uuid4().hex
status, headers, body = send("/internal-api/group/api/groups/summary?memberIds=dj8fy4bewk&memberIds=tu3fgxd45k"
                             "&memberIds=dj8fy4bewk&memberIds=dj8fy4bewk", headers={"X-Request-Id": dedupe_id})
assert status == 200
deduped = json.loads(body)["group"][0]["members"]
assert sorted(m["memberId"] for m in deduped) == ["dj8fy4bewk", "tu3fgxd45k"]
group_logs = docker("logs", "--no-color", "--since", "2m", "group-service")
warn = [line for line in group_logs.splitlines() if "request:" + dedupe_id + " " in line and "event=input.duplicate_ids" in line]
assert len(warn) == 1, "중복 ID 경고 로그가 한 줄이어야 합니다"
assert "duplicateIds=[dj8fy4bewk]" in warn[0] and "duplicateCount=2" in warn[0]
assert "requested=4" in warn[0] and "unique=2" in warn[0] and " WARN " in warn[0]
print("통과: 그룹 중계 목록·상세·원본 400/404/422·중복 ID 제외와 원문 경고 로그·고정 경로", flush=True)
with socket.socket() as probe:
    probe.settimeout(2)
    assert probe.connect_ex(("127.0.0.1", 8082)) != 0
for headers in ([], ["-H", "X-Internal-Service-Key: invalid"]):
    status = docker("exec", "-T", "member-service", "curl", "-sS", "-o", "/dev/null",
                    "-w", "%{http_code}", *headers, "http://172.30.104.3:8082/v3/api-docs")
    assert status == "403"
assert docker("exec", "-T", "member-service", "curl", "--path-as-is", "-sS", "-o", "/dev/null",
              "-w", "%{http_code}", "http://172.30.104.3:8082/actuator/health/../../v3/api-docs") == "403"
for method in ("HEAD", "OPTIONS", "POST"):
    status = docker("exec", "-T", "member-service", "curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}",
                    *(["-I"] if method == "HEAD" else ["-X", method]), "http://172.30.104.3:8082/api/groups/summary")
    assert status == "405", (method, status)
print("통과: 네트워크 소유권·호스트 포트·명세 전달·내부 키 거부 403·그룹 GET 외 405·준비 상태", flush=True)

if args.independence:
    # 단독 모드가 이미 실행 중이면 사용자 작업을 중단하지 않는다.
    for service in ("member", "group"):
        compose = f"services/{service}-service/infra/compose.yaml"
        assert not docker("-f", compose, "ps", "--status", "running", "--quiet").strip(), \
            "단독 모드가 이미 실행 중입니다"
    for service in ("group", "member"):
        compose = f"services/{service}-service/infra/compose.yaml"
        try:
            docker("stop", service + "-service", service + "-db")
            if service == "group":
                # 그룹이 없는 상태에서 멤버를 재기동해 시작 단계 독립성도 검사한다.
                docker("restart", "member-service")
                docker("up", "-d", "--wait", "member-service")
                assert check_http("/actuator/health/readiness", 200, auth=False)["status"] == "UP"
                TOKEN = local_token()
                check_http("/v3/api-docs", 200, auth=False)
                check_http("/internal-docs/group", (503, 504), PROXY_DOWN_CODE, problem_type=PROXY_DOWN_TYPE, auth=False)
                check_http("/api/members", 503, "SERVICE_UNAVAILABLE", problem_type=GROUP_UNAVAILABLE)
                check_http("/internal-api/group/api/groups/summary?memberIds=tu3fgxd45k", (503, 504),
                           PROXY_DOWN_CODE, problem_type=PROXY_DOWN_TYPE)
                check_http("/api/member/tu3fgxd45k", 503, "SERVICE_UNAVAILABLE", problem_type=GROUP_UNAVAILABLE)
                check_http("/api/member/k2m9x0p1q3", 503, "SERVICE_UNAVAILABLE", problem_type=GROUP_UNAVAILABLE)
                check_http("/api/member/missing001", 404, "RESOURCE_NOT_FOUND", problem_type=NOT_FOUND_MEMBER)
                print("통과: 그룹 중단 중 멤버 재기동·자기 명세 유지·업무 조회 503·중계 원인별 503/504", flush=True)
            docker("-f", compose, "up", "-d", "--no-build", "--wait")
            if service == "member":
                assert check_http("/actuator/health/readiness", 200, auth=False)["status"] == "UP"
                TOKEN = local_token()
                check_http("/internal-docs/group", (503, 504), PROXY_DOWN_CODE, problem_type=PROXY_DOWN_TYPE, auth=False)
                check_members()
            print("통과: " + service + " 서비스 소유 Compose 단독 기동", flush=True)
        finally:
            # 데이터 볼륨을 삭제하지 않고 해당 서비스 모드만 원상 복구한다.
            try:
                docker("-f", compose, "stop")
            finally:
                docker("up", "-d", "--wait", service + "-service")
    TOKEN = local_token()
    assert check_http("/internal-docs/group", 200, auth=False)["info"]["title"] == "그룹 서비스 API"
    check_members()
    print("통과: 루트 통합 모드로 복구·명세 및 실제 HTTP 업무 재조회", flush=True)


# 실제 HTTP 로그에서 두 서비스 상관관계와 동시 요청 분리를 검사한다.
import concurrent.futures
trace_ids = ["verify-" + uuid.uuid4().hex for _ in range(4)]
def traced_request(trace_id):
    status, headers, body = send("/api/members?size=1", headers={"X-Request-Id": trace_id})
    assert status == 200 and headers["X-Request-Id"] == trace_id
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    list(pool.map(traced_request, trace_ids))
logs = docker("logs", "--no-color", "--since", "2m", "member-service", "group-service")
for trace_id in trace_ids:
    lines = [line for line in logs.splitlines() if "request:" + trace_id + " " in line]
    member = [line for line in lines if "[service:member-service " in line]
    group = [line for line in lines if "[service:group-service " in line]
    for service_lines in (member, group):
        assert sum("event=request.received" in line for line in service_lines) == 1
        assert sum("event=request.completed" in line for line in service_lines) == 1
        assert any("event=query.completed" in line for line in service_lines)
    remote = [line for line in member if "event=remote.started" in line]
    assert len(remote) == 1 and "operation=group.summary" in remote[0]
    call_id = re.search(r"call:([^ ]+) ", remote[0]).group(1)
    assert all("call:" + call_id + " " in line for line in group)
    assert any("attempt:1]" in line for line in group)
    assert not any("tu3fgxd45k" in line or "example.invalid" in line for line in lines)
    assert not any(TOKEN in line or "Bearer " in line for line in lines)
print("통과: 실제 두 서비스 requestId·callId·시도 전파, 동시 요청 분리, ID·본문·토큰 비노출", flush=True)
