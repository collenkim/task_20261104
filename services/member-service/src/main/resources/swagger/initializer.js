// POL-API-004: 그룹 조회는 멤버의 고정 중계 경로에서 실행한다. 내부 키는 서버만 사용한다.
window.onload = function () {
  const specifications = [
    { name: "멤버 서비스", url: "/v3/api-docs", group: false },
    { name: "그룹 서비스 (멤버 경유)", url: "/internal-docs/group", group: true }
  ];
  const container = document.getElementById("swagger-ui");
  const toolbar = document.createElement("div");
  toolbar.style.cssText = "padding:16px 24px;background:#fafafa;font:16px sans-serif";
  const label = document.createElement("label");
  label.textContent = "API 명세 ";
  const select = document.createElement("select");
  select.id = "service-specification";
  select.setAttribute("aria-label", "API 명세 선택");
  specifications.forEach(function (specification, index) {
    const option = document.createElement("option");
    option.value = String(index);
    option.textContent = specification.name;
    select.appendChild(option);
  });
  label.appendChild(select);
  toolbar.appendChild(label);
  container.before(toolbar);

  function show(index) {
    const specification = specifications[index];
    window.ui = SwaggerUIBundle({
      url: specification.url,
      dom_id: "#swagger-ui",
      deepLinking: false,
      queryConfigEnabled: false,
      validatorUrl: null,
      persistAuthorization: false,
      supportedSubmitMethods: ["get"],
      requestInterceptor: function (request) {
        const target = new URL(request.url, window.location.origin);
        if (target.origin !== window.location.origin) {
          throw new Error("API는 멤버 서비스 주소를 통해 호출합니다.");
        }
        if (specification.group) {
          const allowed = target.pathname === specification.url ||
            target.pathname === "/internal-api/group/api/groups/summary" ||
            /^\/internal-api\/group\/api\/group\/[^/]+$/.test(target.pathname);
          if (!allowed || (request.method || "GET").toUpperCase() !== "GET") {
            throw new Error("등록된 그룹 조회 API만 멤버 서비스를 경유해 실행할 수 있습니다.");
          }
        }
        return request;
      },
      presets: [SwaggerUIBundle.presets.apis],
      layout: "BaseLayout"
    });
  }
  select.addEventListener("change", function () { show(Number(select.value)); });
  show(0);
};
