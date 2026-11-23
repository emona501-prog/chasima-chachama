// מאפשר להתקין את האפליקציה במסך הבית. תמיד טוען גרסה עדכנית מהרשת.
self.addEventListener("install", e => self.skipWaiting());
self.addEventListener("activate", e => self.clients.claim());
self.addEventListener("fetch", () => {});
