// Pure Java integration rules (P2-2): signatures, SSRF guard, retry schedule, templates, state machines, provider
// ports with the SIMULATOR and the unverified partner adapters, NACH file formats, Keycloak admin requests.
// No Spring. The only I/O is behind the HttpTransport port, which the app implements with java.net.http.
dependencies {
    "api"(project(":backend:kernel"))
}
