"""실제 Compose 구조·명세·독립 실행 검사. 비밀 파일을 열거나 값을 출력하지 않는다."""
import argparse
import json
import socket
import sys
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--independence", action="store_true", help="앱·DB를 잠시 단독 모드로 전환 후 복구")
args = parser.parse_args()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def docker(*arguments):
    result = subprocess.run(["docker", "compose", *arguments], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=180)
    if result.returncode:
        raise RuntimeError("Compose 명령 실패: " + " ".join(arguments[:4]))
    return result.stdout


def check_http(path, expected, code=None, no_store=False):
    try:
        with opener.open("http://127.0.0.1:8081" + path, timeout=15) as response:
            status, body = response.status, response.read()
            if no_store:
                assert response.headers.get("Cache-Control") == "no-store"
    except urllib.error.HTTPError as error:
        status, body = error.code, error.read()
    assert status == expected, (path, status, expected)
    parsed = json.loads(body)
    if code:
        assert parsed.get("code") == code
    return parsed


def check_members():
    """고정 local 시드의 업무 API. 실제 개인정보나 본문을 출력하지 않는다."""
    expected = ["tu3fgxd45k", "dj8fy4bewk", "k2m9x0p1q3", "j0n8k2m4p6", "q1w2e3r4t5"]
    page = check_http("/api/members", 200)
    assert [m["id"] for m in page["members"]] == expected, "local 시드 목록과 다릅니다. 기존 데이터는 수정하지 않습니다"
    assert page["page"] == {"number": 0, "size": 20, "totalElements": 5, "totalPages": 1}
    assert len(page["members"][0]["groups"]) == 1
    assert page["members"][2]["groups"] == []
    assert set(page["members"][0]) == {"id", "name", "groups"}
    ids = []
    for number in range(3):
        result = check_http(f"/api/members?page={number}&size=2&sort=name,desc", 200)
        ids.extend(m["id"] for m in result["members"])
    assert ids == ["j0n8k2m4p6", "q1w2e3r4t5", "k2m9x0p1q3", "dj8fy4bewk", "tu3fgxd45k"]
    assert check_http("/api/members?page=10", 200)["members"] == []
    assert check_http("/api/members?size=101", 200)["page"]["size"] == 100
    detail = check_http("/api/member/tu3fgxd45k", 200, no_store=True)
    assert detail["registeredDate"] == "2026-08-15 13:00"
    assert detail["createdAt"] == "2026-08-15 13:01"
    assert detail["createdBy"] == "seed" and len(detail["groups"]) == 1
    assert detail["groups"][0]["joinedAt"] == "2026-08-15 13:00"
    assert detail["groups"][0]["pricing"] == "Premium"
    assert "permissionName" not in detail["groups"][0]
    assert check_http("/api/member/k2m9x0p1q3", 200)["groups"] == []
    for query in ("page=", "size=%20", "page=-1", "size=2147483648", "page=1&page=1",
                  "size=1&size=1", "sort=name,asc&sort=name,asc", "page=21474837&size=100", "sort=id,asc"):
        check_http("/api/members?" + query, 400, "VALIDATION_FAILED")
    check_http("/api/member/BAD", 400, "VALIDATION_FAILED")
    check_http("/api/member/missing001", 404, "NOT_FOUND")


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

assert check_http("/actuator/health/readiness", 200)["status"] == "UP"
member_spec = check_http("/v3/api-docs", 200)
assert "/internal-docs/group" not in member_spec["paths"]
assert set(member_spec["paths"]) == {"/api/members", "/api/member/{id}"}
for server in member_spec.get("servers", []):
    server["url"] = "/"
assert member_spec == json.loads(Path("services/member-service/docs/openapi.json").read_text(encoding="utf-8")), \
    "실제 Compose OpenAPI가 공유 스냅샷과 다릅니다"
check_members()
print("통과: 실제 Compose 목록·상세·단일 소속·정렬·페이지·입력 오류·공유 명세 일치", flush=True)
group_spec = check_http("/internal-docs/group", 200)
assert group_spec["info"]["title"] == "그룹 서비스 API"
assert group_spec["servers"] == [{"url": "/internal-api/group"}] and "security" not in group_spec
expected_group = json.loads(Path("services/group-service/docs/openapi.json").read_text(encoding="utf-8"))
expected_group["servers"] = [{"url": "/internal-api/group"}]
expected_group.pop("security", None)
expected_group.get("components", {}).pop("securitySchemes", None)
expected_group["info"]["description"] = "그룹 API는 멤버 서비스를 경유해 실행합니다. 내부 호출 키는 멤버 서버에서 추가합니다."
assert group_spec == expected_group, "전달 그룹 명세와 제공자 스냅샷이 다릅니다"

summary = check_http("/internal-api/group/api/groups/summary?memberIds=tu3fgxd45k&memberIds=dj8fy4bewk", 200, no_store=True)
assert summary["group"][0]["totalMemberCount"] == 2
assert len(summary["group"][0]["members"]) == 2
detail = check_http("/internal-api/group/api/group/tu3fgxd45k", 200, no_store=True)
assert set(detail) == {"memberId", "groupId", "groupName", "registeredDate", "pricing", "permission"}
assert detail["registeredDate"] == "2026-08-15 13:00"
check_http("/internal-api/group/api/group/k2m9x0p1q3", 404, "GROUP_MEMBERSHIP_NOT_FOUND")
check_http("/internal-api/group/api/groups/summary?memberIds=BAD", 400, "VALIDATION_FAILED")
check_http("/internal-api/group/api/groups/summary?memberIds=%7Bbad%7D", 400, "VALIDATION_FAILED")
check_http("/internal-api/group/actuator/env", 404, "NOT_FOUND")
print("통과: 그룹 중계 목록·상세·원본 400/404·고정 경로", flush=True)
with socket.socket() as probe:
    probe.settimeout(2)
    assert probe.connect_ex(("127.0.0.1", 8082)) != 0
for headers in ([], ["-H", "X-Internal-Service-Key: invalid"]):
    status = docker("exec", "-T", "member-service", "curl", "-sS", "-o", "/dev/null",
                    "-w", "%{http_code}", *headers, "http://172.30.104.3:8082/v3/api-docs")
    assert status == "403"
assert docker("exec", "-T", "member-service", "curl", "--path-as-is", "-sS", "-o", "/dev/null",
              "-w", "%{http_code}", "http://172.30.104.3:8082/actuator/health/../../v3/api-docs") == "403"
print("통과: 네트워크 소유권·호스트 포트·명세 전달·내부 키 거부·준비 상태", flush=True)

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
                assert check_http("/actuator/health/readiness", 200)["status"] == "UP"
                check_http("/v3/api-docs", 200)
                check_http("/internal-docs/group", 503, "GROUP_SERVICE_UNAVAILABLE")
                check_http("/api/members", 503, "GROUP_SERVICE_UNAVAILABLE")
                check_http("/internal-api/group/api/groups/summary?memberIds=tu3fgxd45k", 503, "GROUP_SERVICE_UNAVAILABLE")
                check_http("/api/member/tu3fgxd45k", 503, "GROUP_SERVICE_UNAVAILABLE")
                check_http("/api/member/k2m9x0p1q3", 503, "GROUP_SERVICE_UNAVAILABLE")
                print("통과: 그룹 중단 중 멤버 재기동·자기 명세 유지·그룹 필요 조회 503", flush=True)
            docker("-f", compose, "up", "-d", "--no-build", "--wait")
            if service == "member":
                assert check_http("/actuator/health/readiness", 200)["status"] == "UP"
                check_http("/internal-docs/group", 503, "GROUP_SERVICE_UNAVAILABLE")
                check_members()
            print("통과: " + service + " 서비스 소유 Compose 단독 기동", flush=True)
        finally:
            # 데이터 볼륨을 삭제하지 않고 해당 서비스 모드만 원상 복구한다.
            try:
                docker("-f", compose, "stop")
            finally:
                docker("up", "-d", "--wait", service + "-service")
    assert check_http("/internal-docs/group", 200)["info"]["title"] == "그룹 서비스 API"
    check_members()
    print("통과: 루트 통합 모드로 복구·명세 및 실제 HTTP 업무 재조회", flush=True)


# 실제 HTTP 로그에서 두 서비스 상관관계와 동시 요청 분리를 검사한다.
import concurrent.futures
import re
import uuid
trace_ids = ["verify-" + uuid.uuid4().hex for _ in range(4)]
def traced_request(trace_id):
    request = urllib.request.Request("http://127.0.0.1:8081/api/members?size=1",
                                     headers={"X-Request-Id": trace_id})
    with opener.open(request, timeout=15) as response:
        assert response.status == 200 and response.headers["X-Request-Id"] == trace_id
        response.read()
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
print("통과: 실제 두 서비스 requestId·callId·시도 전파, 동시 요청 분리, ID·본문 비노출", flush=True)
