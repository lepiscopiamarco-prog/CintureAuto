# Cinture Auto (app Android)

App per Android che:

1. **Suona il "ding" delle cinture di sicurezza degli aerei** appena il telefono si collega al Bluetooth dell'auto (di default **"MB Bluetooth"**). Il suono esce dalle casse dell'auto.
2. **Controlla la velocità con il GPS** e, quando superi il limite che hai impostato, **suona un avviso** (tre bip) dalle casse dell'auto.
3. **Funziona a telefono bloccato**: resta attiva in background con una notifica fissa e riparte da sola quando accendi il telefono.

Funziona solo su **Android** (8.0 o successivo). Su iPhone non è possibile.

---

## PARTE 1 — Ottenere il file da installare (APK)

Il file APK viene compilato gratuitamente da GitHub. Non devi installare nulla sul PC.

1. Vai su **github.com** ed entra col tuo account.
2. In alto a destra: **+ → New repository**. Nome: `CintureAuto`. Scegli **Private**. Clicca **Create repository**.
3. Nella pagina che compare clicca **"uploading an existing file"**.
4. Sul PC apri la cartella `CintureAuto` (quella estratta dallo ZIP) e **trascina nella pagina tutto il CONTENUTO** della cartella (le cartelle `app`, `gradle`, `.github` e i file `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, ecc.), non la cartella stessa.
   - La cartella `.github` inizia con un punto e su alcuni computer è nascosta (su Mac: premi `Cmd + Maiusc + .` per mostrarla). Se dopo il caricamento non la vedi nell'elenco dei file su GitHub, vedi la nota qui sotto.
5. In fondo alla pagina clicca **Commit changes**.
6. Vai nel tab **Actions**. Vedrai partire la compilazione **"Compila APK"** (pallino giallo). Attendi 5–10 minuti finché diventa **verde ✓**.
7. Clicca sulla compilazione conclusa, scorri in basso fino a **Artifacts** e scarica **CintureAuto-APK**. Estrai lo ZIP: dentro c'è **app-debug.apk**.

**Se la cartella `.github` non è stata caricata:** su GitHub clicca **Add file → Create new file**, nel nome scrivi esattamente `.github/workflows/build-apk.yml` (i "/" creano le cartelle), incolla il contenuto del file `build-apk.yml.txt` e clicca **Commit changes**. Poi vai in **Actions → Compila APK → Run workflow**.

**Se la compilazione diventa rossa ✗:** clicca sulla compilazione, copia le ultime righe dell'errore e mandale a Claude: lo correggo.

*(Alternativa per chi ha Android Studio: apri la cartella del progetto, poi menu Build → Build APK(s).)*

## PARTE 2 — Installare l'APK sul telefono

1. Porta `app-debug.apk` sul telefono (cavo USB, Google Drive, email a te stesso, WhatsApp "Tu"…).
2. Toccalo dal telefono. Android chiederà di consentire l'installazione da quella app (es. "Chrome" o "File"): consenti.
3. Se compare l'avviso di Play Protect ("app non verificata"): **Installa comunque**. È normale, l'app è tua e non è pubblicata sul Play Store.

## PARTE 3 — Primo avvio (fallo una volta sola, a auto ferma)

All'apertura l'app ti guida:

1. **Permessi**: consenti Posizione (precisa), Bluetooth ("Dispositivi nelle vicinanze") e Notifiche.
2. **Posizione → "Consenti sempre"**. Serve per leggere la velocità a telefono bloccato. Nella schermata che si apre scegli *Consenti sempre*.
3. **Batteria → "Consenti"** (nessuna restrizione), altrimenti Android può spegnere l'app.
4. Nella schermata dell'app, sezione **Auto**: controlla che il nome sia quello del Bluetooth dell'auto (`MB Bluetooth`) oppure usa **"Scegli tra i dispositivi già associati"**.
5. Sezione **Avviso di velocità**: imposta il limite (es. 130 km/h).
6. Usa i pulsanti **▶ Prova** per sentire i suoni dal telefono. Poi regola il volume in auto.

In alto nella schermata "Stato" vedi in tempo reale: servizio attivo, auto connessa, velocità GPS e la spunta dei permessi (tutte ✓ = ok).

**Su alcuni telefoni** (Xiaomi/Redmi/Poco, Huawei, Oppo, Realme, Samsung) serve anche: Impostazioni → App → Cinture Auto → **Batteria: "Nessuna restrizione"** e, se presente, attivare **"Avvio automatico"**. Il pulsante *4 · Impostazioni dell'app* ti porta lì. Dettagli per marca: dontkillmyapp.com.

## PARTE 4 — Come si usa

Non devi fare nulla: sali in auto, il telefono si collega a "MB Bluetooth" e dopo un paio di secondi senti il "ding". Se il GPS è attivo e superi il limite, senti i tre bip (una volta, poi si ripetono ogni N secondi finché resti sopra, impostabile).

## Se qualcosa non va

- **Il ding esce dal telefono, non dall'auto, oppure parte tagliato** → nella sezione *Suono cintura* aumenta *"Attesa dopo la connessione"* (prova 4–6 secondi).
- **Non suona proprio** → controlla che nel campo *Nome Bluetooth* il nome sia identico a quello dell'auto; controlla che il volume multimediale del telefono e dell'autoradio non siano a zero; controlla che *Servizio attivo* sia acceso in alto.
- **Dopo qualche ora l'app non risponde più** → è il risparmio energetico del telefono: rifai i passaggi *Batteria* e *Avvio automatico* (Parte 3).
- **Velocità sempre "in attesa del segnale"** → serve cielo aperto per i primi secondi; verifica che la posizione sia su "Consenti sempre".
- **Il GPS non parte dopo il riavvio del telefono** → apri l'app una volta: la notifica dirà se manca il permesso "Sempre".

## Note oneste

- La velocità è quella del GPS del telefono: è molto precisa, ma può differire di 1–3 km/h dal tachimetro dell'auto.
- Il "ding" è **sintetizzato dall'app** (non è la registrazione di una compagnia aerea): ha lo stesso carattere, ma non è identico.
- Non maneggiare il telefono mentre guidi: configura tutto a auto ferma.
- L'app non invia nessun dato: non ha accesso a internet e tutto resta sul telefono.
