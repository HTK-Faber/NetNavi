# NetNavi – modifiche da fare (v0.7)
Raccolte l'8/11/2026. Non ancora implementate: si attendono gli altri screenshot.

## 1. Dashboard (riferimento: dashboard-riferimento.jpeg)
Ordine dall'alto in basso:
- Header: ritratto del Navi in cornice, nome, "LV 100 MAX", barra EXP con "EXP reale: LV x, n/100".
  A destra: Umore, IA (motore attivo, es. "IA locale (Qwen2.5-0.5B)"), Ricordi (numero).
- Fumetto di benvenuto con icona a stella: "Ciao, sono <nome>. Toccami per parlare, o usa i pulsanti qui sotto."
- Griglia 2x2:
  - "Parla con <nome>" (evidenziato, bordo azzurro luminoso)
  - "App che non usi da molto" con badge rosso numerico
  - "Notifiche recenti"
  - "Le mie caratteristiche" (ex "La mia biblioteca")
- Spazio centrale libero: si vede l'immagine di sfondo.
- Riquadro "Notifiche recenti": ultime 5 (ora, icona app, app, mittente), link "Mostra tutte".
- Riga di 5 tasti rapidi: Sveglia, Timer, Nota, Appuntamento, Impostazioni (ex "Pro", apre le Impostazioni).
- Tasto "Home" in basso al centro.
- Stile: dark fantasy, pannelli scuri semitrasparenti, cornici dorate, testo serif, accenti azzurri.

## 2. Fumetti sulla Home
- Quando il Navi saltella o si anima, ogni fumetto dura al massimo 3 secondi e poi scompare.

## 3. App poco usate
Ogni app proposta ha tre azioni:
- Disinstalla (apre la disinstallazione di sistema)
- Non dirlo più (esclude l'app per sempre)
- Ricordamelo tra 3 giorni (rinvio di 72 ore)

## 4. Nuova sezione: file grandi (foto e video)
- Analisi locale di foto e video, ordinati per dimensione.
- Soglie predefinite: foto > 5 MB, video > 100 MB, modificabili.
- Filtri selezionabili: solo grandi, duplicati (stesso contenuto), foto in serie scattate a pochi secondi
  di distanza (intervallo regolabile), per cartella, per data, solo foto / solo video.
- Comando in dashboard (e comando vocale/testuale).
- Azioni per file: invia a Google Drive / Dropbox / simili, oppure elimina.
- Invio proposto: condivisione Android verso l'app Drive/Dropbox installata.
  L'app NetNavi non usa la rete e non serve l'interruttore Internet.
- Eliminazione: conferma di sistema Android (MediaStore). Opzione "elimina dopo l'invio".
- Permessi: lettura di foto e video.

## 5. Personalizzazione avatar
- C'è un problema: si attendono gli screenshot.

## 6. Sezione "Le mie caratteristiche"
- Contiene: Note, Ricordi, Sprite, Status GDR del personaggio.
- Status GDR: per ora parametri provvisori coerenti con la classe (Tank, Guerriero, Mago, Difensore).

## 7. Sfondo della dashboard
- Sfondo predefinito: lo scenario dark fantasy dello screenshot di riferimento (cavaliere incappucciato e castello).
- Si può cambiare la foto di sfondo quante volte si vuole (Impostazioni). La figura intera del Navi resta separata.

## Stato
- Domande chiarite l'8/11/2026. In attesa degli screenshot sull'errore di personalizzazione avatar.
- Sfondo originale ricevuto: docs/sfondo-dashboard.jpeg.

## Fatto in 0.6.1
- Importatore sprite: le righe scure del personaggio (abiti scuri) non vengono più cancellate come bordi.
  Ora si rimuovono solo linee sottili (<= 6 px) e continue per l'85% dell'immagine.
- Prompt sprite reso generico (niente casco, cappotto rosso, spada blu, mantello).

## Fatto in 0.7 (8/11/2026)
- Nuova dashboard dark fantasy come da riferimento, sfondo predefinito bg_dashboard.jpg, cambiabile senza limiti.
- Fumetti sulla Home: massimo 3 secondi (HomeNavi.BUBBLE_MS).
- App poco usate: Disinstalla / Non dirlo più / Ricordamelo tra 3 giorni (SettingsStore.snoozeApp).
- Foto e video pesanti: MediaScanner + MediaLogic, filtri, invio con condivisione Android, eliminazione con conferma di sistema.
- Le mie caratteristiche: status GDR provvisorio (RpgStatus), note, ricordi, sprite, abilità.
- Tasto Pro rinominato Impostazioni; Guida accessibile dalle Impostazioni.

## Revisioni richieste per la 0.8 (8/11/2026) – NON ancora implementate
1. Fumetti Home oltre 3 s. Codice: say() usa 3000 ms, ma entrance() concatena più frasi (rientro + notifiche perse + app inutilizzate) e
   le notifiche riavviano il fumetto. Possibile anche finestra overlay che non si svuota: nascondere/rimuovere la finestra del fumetto
   con timer indipendente. Verificare che sia installata la 0.7 (prima: 4,2 s + 40 ms/carattere).
2. Sprite più piccoli del Navi predefinito. SpriteImporter scala OGNI riga per conto suo con min(96/altezza, 124/larghezza):
   pose larghe (mantello, spada, corsa) vengono rimpicciolite; un pixel isolato (filigrana, scintille) allarga il riquadro.
   Proposta: scala unica presa dalla riga Idle e applicata a tutte, rimozione dei pezzi isolati, avviso se un frame esce dalla cella.
3. Chat che conosce l'utente: oggi impara solo da frasi esplicite. Aggiungere domande del Navi (nome, interessi, libri letti/in lettura,
   musica, film, giochi, lavoro, ...) con parole chiave salvate nei Ricordi, cadenza limitata, "salta" e "non chiedere più".
4. Home più viva e sonno: oggi nessun rilevamento di inattività né animazione di sonno. Aggiungere stati (attivo, calmo, assonnato,
   dorme) in base a inattività/ora/schermo, riga sprite "sleep", risveglio al tocco o alla notifica, meno salti.
5. Animazioni predefinite come segnaposto: oggi si usano solo con l'avatar provvisorio; con avatar proprio le righe mancanti
   mostrano l'immagine fissa con un piccolo rimbalzo.
6. Ricerca Internet dalla chat: non esiste. Opzioni: apri il browser con la ricerca (nessuna rete dall'app) oppure risultati
   nell'app con interruttore Internet acceso.
