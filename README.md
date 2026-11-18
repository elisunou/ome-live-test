# OME Live – codul original Java

Proiect construit în jurul fișierului `RdsLiveActivity.java` furnizat de utilizator. Fișierul este copiat **identic** (SHA-256 poate fi comparat). Nu se folosesc `MainActivity.kt` sau UI-ul din fostul `ome-live-test`.

- RDSLive: folosește `assets/rdslive-session.js` din APK-ul original și ciclul de sesiune din clasa Java.
- Canale: liste din APK (`rdslive-channels.json`, `tvzone-channels.json`).
- TVZone: adaptorul `TvZoneResolver` este **minimal**; extrage numai un URL media publicat în HTML și nu reproduce integral resolverul compilat din APK.
- IPTV-org: adaptor minimal pentru catalog; nu adaugă surse inventate.
- Configurația `APPLICATION_ID` are sufixul `.rdslivetest` pentru testul Antena din codul Java.

**Limitare importantă:** adaptările claselor lipsă nu sunt echivalente cu codul original compilat în APK. Nu există garanție că un token RDSLive va funcționa în Media3 sau că TVZone va furniza sursa.

## Utilizare
Urcă întregul proiect pe GitHub și compilează cu Actions. La pornire, alege un canal din catalog. APK-ul de debug folosește un ID diferit și nu înlocuiește OME Stream original.
