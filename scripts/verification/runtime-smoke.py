"""실제 Compose 구조·명세·독립 실행 검사. 비밀 파일을 열거나 값을 출력하지 않는다."""
import argparse
import json
import socket
import sys
import subprocess
import urllib.error
import urllib.request

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


def check_http(path, expected, code=None):
    try:
        with opener.open("http://127.0.0.1:8081" + path, timeout=15) as response:
            status, body = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, body = error.code, error.read()
    assert status == expected, (path, status, expected)
    parsed = json.loads(body)
    if code:
        assert parsed.get("code") == code
    return parsed


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
                print("통과: 그룹 중단 중 멤버 재기동·자기 명세 유지·그룹 명세 503", flush=True)
            docker("-f", compose, "up", "-d", "--no-build", "--wait")
            if service == "member":
                assert check_http("/actuator/health/readiness", 200)["status"] == "UP"
                check_http("/internal-docs/group", 503, "GROUP_SERVICE_UNAVAILABLE")
            print("통과: " + service + " 서비스 소유 Compose 단독 기동", flush=True)
        finally:
            # 데이터 볼륨을 삭제하지 않고 해당 서비스 모드만 원상 복구한다.
            try:
                docker("-f", compose, "stop")
            finally:
                docker("up", "-d", "--wait", service + "-service")
    assert check_http("/internal-docs/group", 200)["info"]["title"] == "그룹 서비스 API"
    print("통과: 루트 통합 모드로 복구·명세 재조회", flush=True)
