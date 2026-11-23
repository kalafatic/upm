package upm;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test helper: builds a fully wired UPM server on a temporary app directory. */
final class Harness implements AutoCloseable {
    final Path tmp;
    final App app;
    final WsStore store;
    final State state;
    final Proc proc;
    final Ops ops;
    final Metrics metrics;
    final EnrProxy enr;
    final Diag diag;
    final Dash dash;
    final Server server;

    private Harness(Path tmp, App app, WsStore store, State state, Proc proc, Ops ops,
            Metrics metrics, EnrProxy enr, Diag diag, Dash dash, Server server) {
        this.tmp = tmp;
        this.app = app;
        this.store = store;
        this.state = state;
        this.proc = proc;
        this.ops = ops;
        this.metrics = metrics;
        this.enr = enr;
        this.diag = diag;
        this.dash = dash;
        this.server = server;
    }

    static Harness create(int port, int fallbackRange) throws Exception {
        Path tmp = Files.createTempDirectory("upm-test");
        Path cfg = tmp.resolve("config/application.jsonc");
        Files.createDirectories(cfg.getParent());
        String json = "{\"application\":{\"name\":\"UPM\",\"host\":\"127.0.0.1\",\"port\":" + port
                + ",\"portFallbackRange\":" + fallbackRange
                + ",\"openBrowser\":false,\"theme\":\"light\",\"language\":\"en\","
                + "\"defaultWorkspace\":\"default\",\"defaultRepositoryDir\":\"" + tmp + "\"}}";
        Files.writeString(cfg, json);
        App app = App.load(tmp, "config/application.jsonc");
        WsStore store = new WsStore(app);
        if (!Files.exists(store.workspaceFile("default"))) {
            Config c = store.create("default", "Default", "");
            c.save();
        }
        Main.currentConfig = store.load("default");
        Main.selfPid = ProcessHandle.current().pid();
        State state = new State(app);
        Proc proc = new Proc(app, state);
        Ops ops = new Ops(app, state, proc);
        Metrics metrics = new Metrics();
        EnrProxy enr = new EnrProxy(state);
        Diag diag = new Diag(state, proc);
        Dash dash = new Dash(app);
        Server server = new Server(app, state, ops, proc, diag, enr, dash, metrics, store);
        return new Harness(tmp, app, store, state, proc, ops, metrics, enr, diag, dash, server);
    }

    @Override
    public void close() throws Exception {
        server.stop();
        Main.currentPort = 0;
        WsStore.deleteTree(tmp);
    }
}
