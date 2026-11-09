# NetNavi 0.8 – Migliorie concordate (8/11/2026) – NON ancora implementate

## Carattere del Navi (vale per tutto)
- Non è un servo: è il migliore amico dell'utente. Obiettivo: aiutarlo a migliorare e a sistemare le cose.
- Criticamente costruttivo: se una scelta è poco sensata lo dice con garbo e propone un'alternativa; niente lusinghe automatiche.
- Disinteressato: nessun secondo fine, nessuna spinta a usare di più l'app, nessuna manipolazione; dice "non lo so" quando non sa.
- Esegue comunque le richieste pratiche (note, sveglie, appuntamenti), ma può commentarle (es. "terza sveglia alle 3: tutto bene?").
- Rispetta i limiti: incoraggia senza insistere, rispetta "non ora" / "non chiedermelo più".
- Da applicare in: persona() di AiEngines, frasi fisse di NaviBrain, commenti Home, testi del check di sistema.

## 1. Fumetti Home (max 3 s reali)
- Nascondere/rimuovere davvero la finestra overlay del fumetto alla scadenza, timer indipendente dal resto.
- Pausa minima tra due frasi consecutive; accorpare le frasi del rientro in una sola.
- Le notifiche in arrivo non devono allungare il fumetto all'infinito (coda con limite).

## 2. Dimensione sprite importati
- Scala unica per tutto il set, presa dalla riga Idle (altezza personaggio = 96 px su cella 128), uguale al Navi predefinito.
- Eliminare pixel e pezzi isolati (filigrane, scintille) prima di calcolare il riquadro.
- Avviso se una posa esce dalla cella, con anteprima affiancata al Navi predefinito.

## 3. Chat per conoscere l'utente
- Il Navi fa domande ogni tanto: nome, interessi, libri letti e in lettura, musica, film/serie, giochi, lavoro, abitudini, obiettivi.
- Risposte salvate nei Ricordi con parole chiave e categoria; indice di "affinità" in Le mie caratteristiche.
- Cadenza limitata, tasti "salta" e "non chiedermelo più". (Da decidere: quante al giorno, solo chat o anche fumetto Home.)
- Usa ciò che sa per spronare (es. obiettivi di lettura), non per compiacere.

## 4. Home più viva e sonno
- Stati: attivo, calmo, assonnato, dorme; in base a inattività, ora, schermo, sveglie e Non disturbare.
- Dorme dopo N minuti senza uso (proposta 10), si sveglia al tocco o alla notifica.
- Meno salti; reazioni a batteria/ricarica, momento della giornata; carezza tenendo premuto.

## 5. Nuove animazioni caricabili + segnaposto
- Le righe 1-7 e 15 restano obbligatorie e invariate. Nuove righe facoltative (proposta):
  17 sleep    – Sonno (si siede/sdraia, respiro lento, ciclo)
  18 read     – Lettura dati (consulta uno schermo/libro olografico, ciclo)
  19 scan     – Verifica sistema (onda o griglia di scansione attorno, ciclo)
  20 repair   – Riparazione (lavora con attrezzi o luce, ciclo)
  21 alert    – Allarme / minaccia trovata (si mette in guardia, punto esclamativo visivo)
  22 cheer    – Festa / incoraggiamento (pugno alzato, salto di gioia)
  23 think    – Riflette / dubbio (mano al mento, ciclo)
  24 pet      – Felice per una carezza
- Ogni riga mancante usa un segnaposto: animazione predefinita adatta (es. idle con occhi chiusi per sonno) o movimento generato
  sull'immagine dell'avatar (da decidere quale dei due).
- Aggiornare tabella, regole e prompt nella sezione Info dell'app e nella guida di caricamento.

## 6. Ricerca Internet dalla chat
- Oggi assente. Opzioni da scegliere: (a) "cerca X" apre browser/app Google con la ricerca, l'app non usa la rete;
  (b) risultati in chat solo con interruttore Internet acceso, con avviso che la ricerca esce dal telefono.

## 7. Check del sistema (nuovo)
- Ogni tanto (es. una volta a settimana, o su comando "controlla il sistema") il Navi esegue una verifica e la mostra
  con animazione "scan", risultato a fumetto breve e dettaglio in una nuova sezione della dashboard.
- Limite onesto: Android non permette a un'app normale di leggere dentro le altre app, quindi non è un antivirus a firme.
  Il Navi fa un controllo di "igiene e rischi", tutto in locale:
  - App con permessi pericolosi o insoliti combinati (accessibilità, amministratore dispositivo, lettura notifiche,
    sovrapposizione, SMS, microfono/posizione in background) → possibile spyware/stalkerware.
  - App installate fuori dal Play Store (origine sconosciuta) e app nascoste dal launcher.
  - Aggiornamento di sicurezza di Android vecchio, opzioni sviluppatore / debug USB attivi, blocco schermo assente,
    installazione da origini sconosciute consentita.
  - Spazio quasi pieno, batteria, app che non si aggiornano da molto.
  - Collegamento a Google Play Protect per la scansione vera e propria.
- Riparazione: per ogni problema spiega rischio e soluzione e apre la schermata giusta delle Impostazioni
  o la richiesta di disinstallazione; non agisce mai da solo.
- Opzionale (solo con Internet acceso): elenco offline di impronte di malware noti da confrontare con le app installate.
- Esperienza e livello: il check può far guadagnare punti al Navi.

## 8. Altre idee concordate
- Avviso spazio libero basso; riepilogo serale (notifiche, appuntamenti, sveglie); orari di sonno legati alle sveglie
  e al Non disturbare; anteprima a tutto schermo per le foto a raffica; "salta" e "non chiedermelo più" per ogni domanda.

## Decisioni (risposte dell'utente) e stato in 0.8-amico
- Ricerca: risultati in chat con l'interruttore Internet acceso (DuckDuckGo + Wikipedia), annunci esclusi, ordinati per
  affidabilità della fonte, max 5, uno per sito, tocco = apre nel browser. Internet spento: propone di aprire il browser. FATTO
- Segnaposto: le animazioni mancanti vengono generate dall'immagine caricata (idle caricato o avatar). FATTO (ProceduralAnim)
- Domande: max 2 al giorno, ad almeno 3 ore l'una dall'altra, all'apertura della chat; "salta", "non chiedermelo più",
  "fammi una domanda". Affinità in Le mie caratteristiche. FATTO
- Sonno: dopo 15 minuti di inutilizzo; si sveglia con tocchi, scorrimenti, accensione schermo, notifiche. FATTO
- Check sistema: su comando o pianificato di notte (3-5); elenco app spia note scaricato da Internet
  (AssoEchap/stalkerware-indicators), aggiornato al massimo una volta a settimana. Pulsante "Controllo sistema" in dashboard. FATTO
- Fumetti: spariscono dopo 3 s (controllo di sicurezza), pausa tra fumetti. FATTO
- Sprite importati: stessa altezza del Navi predefinito, pulizia puntini. FATTO
- Carattere: amico critico e costruttivo, nessun secondo fine; commenti su orari tardi e sveglie con poche ore di sonno. FATTO
- Righe sprite 17-24 (sonno, lettura, scansione, riparazione, allarme, festa, pensiero, coccola). FATTO
