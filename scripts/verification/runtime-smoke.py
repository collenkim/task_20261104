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
    """고정 local 시드의 fixture 업무 API. 실제 개인정보나 본문을 출력하지 않는다."""
    expected = ["tu3fgxd45k", "dj8fy4bewk", "k2m9x0p1q3", "j0n8k2m4p6", "q1w2e3r4t5"]
    page = check_http("/api/members", 200)
    assert [m["id"] for m in page["members"]] == expected, "local 시드 목록과 다릅니다. 기존 데이터는 수정하지 않습니다"
    assert page["page"] == {"number": 0, "size": 20, "totalElements": 5, "totalPages": 1}
    assert len(page["members"][0]["groups"]) == 2
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
    assert detail["registeredDate"] == "2026-08-15T04:00:59.123456Z"
    assert detail["createdAt"] == "2026-08-15T04:01:00.654321Z"
    assert detail["createdBy"] == "seed" and len(detail["groups"]) == 2
    assert detail["groups"][1]["joinedAt"] is None and detail["groups"][1]["pricing"] is None
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
print("통과: 실제 Compose 목록·상세·다중 소속·정렬·페이지·입력 오류·공유 명세 일치", flush=True)
group_spec = check_http("/internal-docs/group", 200)
assert group_spec["info"]["title"] == "그룹 서비스 API"
assert "servers" not in group_spec and "security" not in group_spec
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
                check_members()
                print("통과: 그룹 중단 중 멤버 재기동·fixture 업무 조회 유지·그룹 명세 503", flush=True)
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
    print("통과: 루트 통합 모드로 복구·명세 및 fixture 업무 재조회", flush=True)
