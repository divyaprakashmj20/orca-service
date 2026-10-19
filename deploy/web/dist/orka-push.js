/* Standard Web Push handler. No API responses, credentials or guest pages are cached. */
const STATE_CACHE = "orka-push-state-v1";
const STATE_KEY = "/__orka_push_state";
let pending = Promise.resolve();
const serial = (task) => {
  const work = pending.then(task, task);
  pending = work.catch(() => {});
  return work;
};
async function readState() {
  const stored = await (await caches.open(STATE_CACHE)).match(STATE_KEY);
  return stored ? stored.json() : { owner: "", lastAlert: 0 };
}
async function writeState(state) {
  await (
    await caches.open(STATE_CACHE)
  ).put(STATE_KEY, new Response(JSON.stringify(state)));
}
function destination(data) {
  const id = (v) => /^[1-9]\d{0,15}$/.test(String(v || ""));
  if (data.kind === "food_order" && id(data.foodOrderId) && id(data.hotelId))
    return "/food?hotel=" + data.hotelId + "&order=" + data.foodOrderId;
  if (data.kind === "service_request" && id(data.requestId))
    return "/requests?request=" + data.requestId;
  return "/";
}
function safePath(value) {
  return typeof value === "string" &&
    /^(?:\/|\/requests\?request=[1-9]\d{0,15}|\/food\?hotel=[1-9]\d{0,15}&order=[1-9]\d{0,15})$/.test(
      value,
    )
    ? value
    : "/";
}
self.addEventListener("install", (event) =>
  event.waitUntil(self.skipWaiting()),
);
self.addEventListener("activate", (event) =>
  event.waitUntil(self.clients.claim()),
);
self.addEventListener("message", (event) => {
  if (event.data?.type !== "ORKA_PUSH_STATE") return;
  event.waitUntil(
    serial(async () => {
      const previous = await readState();
      const owner = String(event.data.owner || "").slice(0, 128);
      await writeState({
        owner,
        lastAlert: owner && owner === previous.owner ? previous.lastAlert : 0,
      });
      if (!event.data.owner)
        for (const notice of await self.registration.getNotifications({
          tag: "orka-work",
        }))
          notice.close();
      event.ports?.[0]?.postMessage({ ok: true });
    }),
  );
});
self.addEventListener("push", (event) => {
  event.waitUntil(
    serial(async () => {
      let data = {};
      try {
        data = event.data?.json()?.data || {};
      } catch {
        /* Always show a safe fallback: Safari requires a visible notification. */
      }
      const state = await readState();
      if (!state.owner) {
        await self.registration.showNotification("Orka notifications are off", {
          body: "Open Orka and sign in to set up this installation.",
          tag: "orka-work",
          data: { path: "/" },
          icon: "/icons/orka-192.png",
          silent: true,
        });
        return;
      }
      const now = Date.now(),
        burst = now - state.lastAlert < 30000;
      const test = data.eventType === "WEB_PUSH_TEST";
      const title = test
        ? "Orka server notification test"
        : burst
          ? "New hotel activity"
          : data.kind === "food_order"
            ? "Food orders need attention"
            : "Service requests need attention";
      await self.registration.showNotification(title, {
        body: test
          ? "This notification was sent by the Orka backend."
          : "Open Orka to review your hotel queues.",
        icon: "/icons/orka-192.png",
        badge: "/icons/orka-192.png",
        tag: "orka-work",
        renotify: !burst,
        silent: burst,
        data: { path: burst || test ? "/" : destination(data) },
      });
      if (!burst) await writeState({ ...state, lastAlert: now });
      for (const client of await self.clients.matchAll({
        type: "window",
        includeUncontrolled: true,
      }))
        client.postMessage({ type: "ORKA_PUSH_RECEIVED" });
    }),
  );
});
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    (async () => {
      const path = safePath(event.notification.data?.path);
      const url = new URL(path, self.location.origin).href;
      const clients = await self.clients.matchAll({
        type: "window",
        includeUncontrolled: true,
      });
      const existing = clients.find(
        (client) =>
          client.url.startsWith(self.location.origin + "/") &&
          !new URL(client.url).pathname.startsWith("/guest/"),
      );
      if (existing) {
        await existing.navigate(url);
        await existing.focus();
      } else await self.clients.openWindow(url);
    })(),
  );
});
