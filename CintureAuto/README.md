# Cinture Auto (app Android) — versione 1.2

App per Android che:

1. **Suona il "ding" delle cinture di sicurezza degli aerei** appena il telefono si collega al Bluetooth dell'auto (di default **"MB Bluetooth"**), e **subito dopo una voce femminile dice "Allacciare le cinture di sicurezza"**. Tutto esce dalle casse dell'auto.
2. **Controlla la velocità con il GPS** e suona un avviso (tre bip) quando superi il limite. Il **limite si adegua da solo alla strada** in cui ti trovi (dati OpenStreetMap); dove non è noto usa quello che imposti tu.
3. **Ricorda dov'è parcheggiata l'auto**: quando il Bluetooth si disconnette salva la posizione e te la mostra su mappa.
4. **Tiene il registro dei viaggi**: durata, chilometri, velocità massima e quante volte hai superato il limite, con **esportazione in Excel**.
5. **Funziona a telefono bloccato**: resta attiva in background con una notifica fissa e riparte da sola quando accendi il telefono.

Funziona solo su **Android** (8.0 o successivo).

---

## NOVITÀ DELLA VERSIONE 1.2

- **Dov'è parcheggiata l'auto**: quando scendi e il Bluetooth dell'auto si disconnette, l'app salva il punto in cui si trovava il telefono. Dalla schermata puoi vederlo su mappa, farti guidare **a piedi** fino all'auto o **condividerlo** (WhatsApp, SMS…).
- **Registro dei viaggi**: per ogni viaggio salva data, durata, tempo in movimento, chilometri, velocità massima, velocità media e **quante volte è suonato l'avviso di limite superato**. Con un tocco li esporti in un **vero file Excel (.xlsx)**, con totali, filtri e link alla mappa del punto di arrivo.

(Dalla versione 1.1: voce dopo il "ding", limite automatico secondo la strada, tolleranza, firma fissa dell'app.)

---

## PARTE 1 — Aggiornare l'app (hai già il repository su GitHub)

1. Estrai questo ZIP sul PC.
2. Su GitHub apri il repository **CintureAuto** → **Add file → Upload files**.
3. Trascina nella pagina la cartella **`app`** (la cartella intera, non i file dentro). Sostituisce i file vecchi e aggiunge quelli nuovi, compresa la chiave di firma `app/debug.keystore`.
4. In fondo clicca **Commit changes**. La compilazione parte da sola.
5. Vai nel tab **Actions** e attendi 5–10 minuti che la compilazione diventi verde ✓. Aprila e scarica **CintureAuto-APK** da "Artifacts" in fondo alla pagina. Estrai lo ZIP: dentro c'è `app-debug.apk`.
6. Se diventa rossa ✗: apri la compilazione, copia le ultime righe dell'errore (o fai uno screenshot) e mandale a Claude.

### ⚠️ Solo questa volta: disinstalla la vecchia versione

Questa versione è firmata con una chiave nuova e fissa, e Android non permette di installarla sopra la versione 1.0 (comparirebbe "App non installata"). Quindi:

1. Sul telefono: Impostazioni → App → **Cinture Auto** → **Disinstalla**.
2. Installa il nuovo `app-debug.apk` (Parte 2).
3. Rifai la configurazione iniziale (Parte 3): permessi, posizione "Sempre", batteria. Le impostazioni tornano ai valori predefiniti.

Dalle prossime versioni in poi non servirà più: si installa direttamente sopra.

---

## PARTE 2 — Installare l'APK sul telefono

1. Porta `app-debug.apk` sul telefono (cavo USB, Google Drive, email a te stesso, WhatsApp "Tu"…).
2. Toccalo dal telefono. Android chiederà di consentire l'installazione da quella app (es. "Chrome" o "File"): consenti.
3. Se compare l'avviso di Play Protect ("app non verificata"): **Installa comunque**. È normale, l'app è tua e non è pubblicata sul Play Store.

## PARTE 3 — Primo avvio (fallo una volta sola, a auto ferma)

All'apertura l'app ti guida:

1. **Permessi**: consenti Posizione (precisa), Bluetooth ("Dispositivi nelle vicinanze") e Notifiche.
2. **Posizione → "Consenti sempre"**. Serve per leggere la velocità, ricordare il parcheggio e registrare i viaggi a telefono bloccato.
3. **Batteria → "Consenti"** (nessuna restrizione), altrimenti Android può spegnere l'app.
4. Sezione **Auto**: controlla che il nome sia quello del Bluetooth dell'auto (`MB Bluetooth`) oppure usa **"Scegli tra i dispositivi già associati"**.
5. Sezione **Voce**: tocca **"Scegli la voce"** e prova le voci una a una (toccandone una la senti subito); poi regola velocità, tono e volume. Con **"▶ Prova suono + voce"** senti la sequenza completa.
6. Sezione **Avviso di velocità**: lascia attivo il **limite automatico**; imposta la tolleranza e il limite manuale (usato solo dove la strada non ha un limite noto).
7. Sezioni **Dov'è parcheggiata l'auto** e **Registro dei viaggi**: sono già attive; spegni l'interruttore di quella che non ti serve.

Nella schermata "Stato" vedi in tempo reale: servizio attivo, auto connessa, velocità GPS, **limite in uso**, **viaggio in corso** e la spunta dei permessi (tutte ✓ = ok).

**Su alcuni telefoni** (Xiaomi/Redmi/Poco, Huawei, Oppo, Realme, Samsung) serve anche: Impostazioni → App → Cinture Auto → **Batteria: "Nessuna restrizione"** e, se presente, attivare **"Avvio automatico"**. Il pulsante *4 · Impostazioni dell'app* ti porta lì. Dettagli per marca: dontkillmyapp.com.

## PARTE 4 — Come si usa

Non devi fare nulla: sali in auto, il telefono si collega a "MB Bluetooth", dopo un paio di secondi senti il "ding" e poi la voce. Mentre guidi, se superi il limite della strada più la tolleranza, senti i tre bip (una volta, poi si ripetono ogni N secondi finché resti sopra, impostabile). Quando spegni l'auto e il Bluetooth si disconnette, posizione e viaggio vengono salvati da soli.

### Trovare l'auto

Apri l'app → scheda **Dov'è parcheggiata l'auto**. Vedi quando è stata salvata la posizione e le coordinate. I pulsanti:

- **Mostra l'auto su mappa**: apre l'app di mappe del telefono sul punto.
- **Portami all'auto a piedi**: apre Google Maps con le indicazioni a piedi (serve la connessione dati).
- **Condividi la posizione**: manda il link della mappa a chi vuoi.
- **Cancella la posizione salvata**.

Se l'auto è in un garage o in una galleria, il GPS non funziona: l'app salva l'**ultimo punto in cui il GPS aveva segnale** e ti avvisa con ⚠ che la posizione è approssimata (l'auto sarà vicina, ma non esattamente lì).

### Registro dei viaggi ed esportazione in Excel

Apri l'app → scheda **Registro dei viaggi**: vedi il totale (viaggi, chilometri, ore alla guida, superamenti) e gli ultimi 5 viaggi.

Per ottenere il file Excel:

1. Tocca **Esporta in Excel (.xlsx)**.
2. Si apre la schermata di Android per salvare un file: scegli la cartella (per esempio *Download* o *Google Drive*) e tocca **Salva**.
3. Compare «File salvato»: tocca **Apri** (serve Excel o Fogli Google installati) oppure ritrovalo nella cartella scelta. Dal PC lo apri con Excel normalmente.

Nel file, foglio **Viaggi**: una riga per viaggio (data, ora di inizio e fine, durata, tempo in movimento, distanza, velocità massima, velocità media, superamenti del limite, link «Apri mappa» sul punto di arrivo), una riga **Totale** con le formule e i filtri sulle colonne. Ogni esportazione contiene tutti i viaggi registrati fino a quel momento.

**Cosa conta come viaggio.** Inizia quando l'auto si collega e finisce quando si disconnette. Si tengono solo i viaggi di almeno **300 metri e 1 minuto** (così il Bluetooth che si riconnette non crea viaggi finti). I chilometri sono ricavati dalle posizioni GPS: sono molto vicini al contachilometri dell'auto, ma possono differire di qualche punto percentuale (di più in gallerie o con poco segnale). La velocità media è calcolata sul tempo in cui l'auto si muove davvero (esclusi semafori e soste).

**Superamenti del limite.** Conta quante volte è *iniziato* un superamento (cioè quante volte è suonato l'avviso per la prima volta), non le ripetizioni dell'avviso mentre resti sopra il limite. Se durante un viaggio l'avviso di velocità era spento, nell'Excel compare «n.d.». Il limite è quello della strada più la tolleranza che hai impostato, quindi il conteggio dipende da quei dati (vedi la sezione sul limite automatico: non sono ufficiali).

---

## Come funziona il limite automatico (e cosa NON può sapere)

Il telefono manda a un servizio pubblico di OpenStreetMap la tua posizione **arrotondata a circa 100 metri** e riceve le strade dei dintorni con i loro limiti. Poi, a ogni lettura del GPS, abbina la tua posizione alla strada giusta (tenendo conto anche della direzione in cui vai, per distinguere strade parallele e incroci) e ne legge il limite. Le richieste di rete sono contenute: circa una ogni 15–30 secondi mentre guidi (ogni richiesta scarica le strade dei dintorni, che servono per le letture successive), e nessuna quando sei fermo nella stessa zona.

- Dove la strada **non ha un limite scritto** nei dati: per autostrade (130), superstrade (110) e strade residenziali (50) l'app deduce il valore e lo segnala come «stimato dal tipo di strada». Per tutte le altre usa il **limite manuale**: in Italia, senza altri dati, non si può sapere se una strada è dentro o fuori dal centro abitato.
- La copertura dei dati **varia da zona a zona**: provala sulle tue strade abituali e guarda cosa scrive nella riga "Limite" della schermata.
- **Non conosce** i limiti temporanei (cantieri), i pannelli a messaggio variabile, i limiti ridotti per pioggia o neve.
- I dati possono essere in ritardo o sbagliati: **fanno fede sempre i cartelli stradali**, non l'app.
- Cambi di limite: per non farsi ingannare da un incrocio o da un salto del GPS, l'app cambia limite dopo 3 letture concordi (circa 3 secondi).

## Privacy: cosa resta sul telefono e cosa esce

- **Posizione dell'auto e registro dei viaggi** restano **solo sul telefono**, nella memoria privata dell'app (nessun'altra app può leggerli, e il backup automatico di Android è disattivato per questa app). L'app non li invia a nessuno. Escono dal telefono solo se tocchi tu «Condividi la posizione» o «Esporta in Excel».
- Il file Excel contiene gli orari e i **punti di arrivo** dei tuoi viaggi: trattalo come un dato personale, soprattutto se lo condividi. Una volta salvato fuori dall'app, resta lì finché non lo cancelli tu.
- **Limite automatico**: con l'opzione accesa, mentre il GPS è attivo, l'app contatta i server pubblici Overpass di OpenStreetMap (prima `overpass.private.coffee`, di riserva `overpass-api.de`) inviando solo la posizione approssimata (circa 100 m). Nessun account, nessun identificativo, nient'altro. Se non vuoi usare la rete, spegni "Limite automatico" e resta solo il limite manuale.
- «Portami all'auto a piedi» e «Mostra su mappa» aprono Google Maps (o l'app di mappe che hai): in quel caso la posizione dell'auto viene comunque letta da quell'app, con le sue regole.
- Per cancellare i dati: «Cancella la posizione salvata», «Cancella tutti i viaggi», oppure disinstalla l'app.

**Dati.** I consumi dovrebbero essere bassi, ma non li ho potuti misurare: controllali dopo i primi viaggi in Impostazioni → Utilizzo dati e Batteria.

Dati delle strade: © OpenStreetMap contributors (licenza ODbL).

## Se qualcosa non va

- **La voce non parla** → in "Voce" tocca "▶ Prova suono + voce": se compare un messaggio, segui quello (di solito manca la voce italiana: usa "Impostazioni di sintesi vocale del telefono" e scarica l'italiano; se manca il motore, installa "Sintesi vocale di Google" dal Play Store).
- **La voce non mi piace** → "Scegli la voce" e prova le altre; riduci la velocità al 85–90%; tienine il volume sotto quello del suono per un tono più "tenue".
- **Il ding esce dal telefono, non dall'auto, oppure parte tagliato** → nella sezione *Suono cintura* aumenta *"Attesa dopo la connessione"* (prova 4–6 secondi).
- **Non suona proprio** → controlla che nel campo *Nome Bluetooth* il nome sia identico a quello dell'auto; che il volume multimediale del telefono e dell'autoradio non sia a zero; che *Servizio attivo* sia acceso in alto.
- **Il limite resta quello manuale** → nella riga "Limite" leggi il motivo: "Dati della strada non raggiungibili" (manca la connessione dati), "Limite di questa strada non noto" (la strada non ha un limite nei dati).
- **La posizione dell'auto non si aggiorna** → l'app la salva quando il Bluetooth si disconnette: se la tua autoradio resta collegata dopo lo spegnimento (alcune lo fanno per qualche minuto) la posizione si salva solo al momento della disconnessione vera. Controlla anche che «Salva la posizione quando scendo dall'auto» sia acceso e che la posizione sia su «Consenti sempre».
- **Nessun viaggio registrato** → il viaggio compare nell'elenco solo dopo la disconnessione dell'auto ed è tenuto solo se supera 300 m e 1 minuto. Durante la guida lo vedi nella riga «Viaggio in corso» in alto.
- **«Esporta in Excel» dice che non può salvare** → scegli una cartella del telefono (per esempio *Download*) nella schermata di salvataggio. Se non si apre nessuna schermata, il telefono non ha il gestore file di Android: installa «File» di Google.
- **Il file Excel non si apre dal pulsante «Apri»** → il file è comunque salvato: installa Fogli Google o Microsoft Excel, oppure aprilo dal PC.
- **Dopo qualche ora l'app non risponde più** → è il risparmio energetico del telefono: rifai i passaggi *Batteria* e *Avvio automatico* (Parte 3).
- **Velocità sempre "in attesa del segnale"** → serve cielo aperto per i primi secondi; verifica che la posizione sia su "Consenti sempre".
- **Il GPS non parte dopo il riavvio del telefono** → apri l'app una volta: la notifica dirà se manca il permesso "Sempre".
- **"App non installata" durante l'aggiornamento** → vedi il riquadro "Solo questa volta: disinstalla la vecchia versione".

## Note oneste

- La velocità è quella del GPS del telefono: è molto precisa, ma può differire di 1–3 km/h dal tachimetro dell'auto.
- Il "ding" è **sintetizzato dall'app** (non è la registrazione di una compagnia aerea): ha lo stesso carattere, ma non è identico.
- La voce è quella di **sintesi vocale del telefono**: la qualità dipende dalle voci installate.
- Il registro dei viaggi è uno **strumento personale di promemoria**, non un registro ufficiale: i dati vengono dal GPS di un telefono e non hanno valore di prova (per esempio per rimborsi chilometrici o contestazioni) senza verifica.
- Se Android chiude l'app a metà viaggio, il viaggio viene recuperato al riavvio ma può risultare diviso in due.
- Non maneggiare il telefono mentre guidi: configura tutto a auto ferma.
