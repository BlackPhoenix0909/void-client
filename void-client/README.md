# Void Client — Grundgerüst

Architektur nach Lunar-/Feather-Client-Vorbild: eigenständiger JavaFX-Launcher
(eigener Prozess), der Instanzen/Profile verwaltet, Mods über die Modrinth-API
installiert und Minecraft als Subprozess startet.

## Struktur

```
src/main/kotlin/com/voidclient/
  Main.kt                          Einstiegspunkt (JavaFX Application)
  auth/
    AuthModels.kt                  Datenmodelle (DeviceCode, Session, AuthResult)
    MicrosoftAuthManager.kt        MS OAuth Device-Code-Flow -> XBL -> XSTS -> MC
  instance/
    Instance.kt                    Profil-Datenmodell
    InstanceManager.kt             Anlegen/Laden/Speichern von Profilen
  mods/
    ModrinthModels.kt               Datenmodelle fuer Modrinth-API-Antworten
    ModrinthIntegrationManager.kt  searchMods() / installMod() inkl. Dependency-Resolution
  launch/
    GameLauncher.kt                Fabric-Profil laden, Libraries aufloesen, Prozess starten
  ui/
    MainController.kt              Navigation, Fade-Transitions, Partikel-Splash
    HomeController.kt              Profilliste + Start
    ModsController.kt              Such-UI, Installation
    SettingsController.kt          Login-UI (Device-Code-Flow)
    VoidClientServices.kt          Zentraler Service-Locator

src/main/resources/
  fxml/{main,home,mods,settings}.fxml
  css/void-theme.css               Schwarz-Violett-Theme (identisch zum HTML-Preview)
```

## Setup

```bash
./gradlew run                       # startet den Launcher
./gradlew stageCoreMods -PmcVersion=1.21.1   # laedt aktuelle Sodium/Iris-Jars nach build/coremods/
```

Vorher in `MicrosoftAuthManager.kt` eine echte Azure-App-Client-ID eintragen
(App-Registrierung unter https://portal.azure.com, Redirect-Typ "Public client",
kein Client-Secret noetig für den Device-Code-Flow).

## Bewusst offen gelassen (Blaupause, kein Full-Build)

- **Vanilla-Client-Jar-Beschaffung**: Mojang Version-Manifest (`launchermeta.mojang.com`)
  wird noch nicht abgefragt; `GameLauncher` erwartet aktuell einen bereits vorhandenen
  Pfad zur `client.jar`.
- **LWJGL-Natives-Extraktion**: fehlt, ist aber gut dokumentiert in Referenzprojekten
  wie `minecraft-launcher-lib`.
- **Asset-Index-Download**: fehlt (Sounds/Sprachdateien), betrifft nicht den reinen
  Modding-Workflow.
- **Session-Verschlüsselung**: Access-/Refresh-Token liegen aktuell im Klartext unter
  `~/.voidclient/session.json` — für eine echte Auslieferung durch OS-Keystore ersetzen.
- **SHA1-Verifikation** heruntergeladener Mod-Jars ist vorbereitet (Hash liegt in
  `ModFile.sha1`), wird aber noch nicht geprüft.
- **Profil-Auswahl in der Mods-Ansicht**: installiert aktuell immer in das erste
  vorhandene Profil; braucht noch einen Dropdown/Profil-Switcher.

## Von GitHub zur .exe

1. Projekt auf GitHub pushen (dieses ZIP entpackt als Repo-Root).
2. `.github/workflows/build-windows.yml` ist bereits enthalten und baut auf einem
   `windows-latest`-Runner automatisch bei jedem Tag-Push (`git tag v0.1.0 && git push --tags`)
   oder manuell über "Run workflow" im Actions-Tab.
3. Ablauf im Workflow: `gradle shadowJar` (Fat-Jar mit allen Abhängigkeiten) →
   `jpackage --type exe` (JDK-Bordmittel seit Java 14, bündelt automatisch eine
   passende Java-Laufzeit mit ein) → Upload als Artefakt + Anhang an das GitHub-Release.
4. **Vor dem ersten Lauf ergänzen:**
   - `src/main/resources/images/void-icon.ico` — ein eigenes 256x256-.ico-Icon
     ablegen (im Repo aktuell nicht enthalten, sonst schlägt `jpackage` fehl).
   - Echte Azure-App-Client-ID in `MicrosoftAuthManager.kt` eintragen.
5. `windows-latest`-Runner bringen WiX Toolset bereits vorinstalliert mit, das
   `jpackage` für `--type exe` intern braucht — kein zusätzlicher Setup-Schritt nötig.
6. Kein Gradle-Wrapper im Repo nötig: der Workflow nutzt `gradle/actions/setup-gradle`,
   das Gradle direkt auf dem Runner bereitstellt. Für lokale Builds trotzdem empfehlenswert:
   `gradle wrapper --gradle-version 8.10` einmalig ausführen und committen.

### Wichtig zu Codesigning

Ohne Code-Signing-Zertifikat zeigt Windows SmartScreen beim ersten Start eine
Warnung ("Windows hat den PC geschützt"). Das ist normal für unsignierte Exe-Dateien
und kein Bug im Build. Für eine breitere Verteilung braucht es ein Code-Signing-
Zertifikat (z.B. von DigiCert/Sectigo) und einen zusätzlichen Signing-Schritt im
Workflow (`signtool.exe`, das auf den Runnern vorinstalliert ist).

## Design-Preview

`void-client-preview.html` (separat geteilt) zeigt das visuelle Design 1:1 in HTML/CSS —
Partikel-Splash, Hover-Glow, Farbschema — als Diskussionsgrundlage, bevor es 1:1 ins
JavaFX-CSS (`void-theme.css`) übertragen wird.
