// POL-API-004: 그룹 명세는 조회만 한다. 브라우저에 내부 키를 전달하지 않는다.
window.onload = function () {
  const specifications = [
    { name: "멤버 서비스", url: "/v3/api-docs", readOnly: false },
    { name: "그룹 서비스 (내부 · 읽기 전용)", url: "/internal-docs/group", readOnly: true }
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
      supportedSubmitMethods: specification.readOnly
        ? [] : ["get", "head", "post", "put", "patch", "delete", "options", "trace"],
      requestInterceptor: function (request) {
        // 읽기 전용 화면에서는 명세 GET 외의 요청을 보내지 않는다.
        if (specification.readOnly &&
            (new URL(request.url, window.location.origin).href !==
              new URL(specification.url, window.location.origin).href ||
              (request.method || "GET").toUpperCase() !== "GET")) {
          throw new Error("그룹 API는 멤버 서버를 통해 호출합니다.");
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
