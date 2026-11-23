# חסימה חכמה 🛡️
מערכת שליטת הורים לנגן/טלפון אנדרואיד של הילד – מהטלפון של ההורה, בזמן אמת.

- **בנגן:** האפליקציה `chasima-chachama.apk` מעמוד ה־[Releases](../../releases/latest).
- **אצל ההורה:** האתר `docs/` (GitHub Pages) – אפשר "להוסיף למסך הבית".

## כללי מסד הנתונים (Firebase → Realtime Database → Rules)
```json
{
  "rules": {
    "pair": { "$c": { ".read": true, ".write": true } },
    "d":    { "$id": { ".read": true, ".write": true } }
  }
}
```

חי אפליקציות 💙
