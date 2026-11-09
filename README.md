# NetNavi – pacchetto completo (fase 1 + sprite + fase 2)

Apri la cartella `NetNavi` in Android Studio (File > Open), attendi il Sync e premi Run.
Verificato: `assembleDebug` e test dell'interprete comandi compilano e passano (AGP 8.7.2, Kotlin 2.0.21, Gradle 8.9, JDK 17).

## Cosa contiene
- Fase 1: creazione unica dell'avatar (ritaglio ML Kit, classe Tank/Guerriero/Mago/Difensore, mezzo busto, pixel art), trasferimento cifrato `.navi` (spostamento, non copia), `allowBackup=false`.
- Sprite: master bloccata (righe 1-7 e 15) + righe aggiuntive; riga 15 solo per il parlato, espressioni in righe separate (23 Riflette, 25 Sorpreso, 26 Saluta); importatore v2 (toglie riquadri neri, sceglie la riga se l'immagine ne ha più di una, divide per spazi vuoti); Guida con regole, tabella e prompt.
- Fase 2:
  - Notifiche annunciate con app e mittente (mai il testo), frasi variabili, budget giornaliero, silenzio con Non disturbare, chiamate e sveglie che suonano.
  - Navi che passeggia/corre/salta/vola nella home con fumetti; overlay piccolo sopra le altre app (trascinabile, tocco = chat).
  - Chat testuale e vocale; comandi offline: sveglie, timer, note, appuntamenti, prossima sveglia, apri app, cos'è un'app, app inutilizzate.
  - Conferma obbligatoria prima di creare sveglie, timer, note ed eventi.
  - Abitudini: suggerisce l'app che apri di solito a quell'ora (min. 5 volte, 50%).
  - IA: locale (Gemma .task via MediaPipe) oppure Gemini con chiave gratuita, oppure offline.
  - Riquadro "Chiama Navi" nelle Impostazioni rapide.

## IA locale sul Moto g32 (4 GB)
1. Su Hugging Face (account gratuito) apri `litert-community/gemma-3-270m-it` e accetta la licenza Gemma.
2. Scarica il file `.task` (es. `gemma3-270m-it-q8.task`). Alternativa migliore ma lenta: `litert-community/Gemma3-1B-IT`, file int4 `.task`.
3. Nell'app: Impostazioni > IA locale > Importa modello .task > "Prova IA locale".
Se la RAM libera è sotto ~700 MB l'IA locale non parte e il Navi usa Gemini o le risposte offline.

## Novità 0.5: il Navi vive sopra la Home del telefono
NetNavi non è più un launcher. Resta la Home del telefono (es. Motorola) con sfondo e icone tue; il Navi compare sopra, trasparente:
- cammina, salta sulle icone e sui widget, le osserva e a volte le commenta;
- quando cambi pagina se ne accorge (scende dall'icona, si guarda intorno) ed esplora la nuova;
- quando apri un'app sparisce; torna con la Home e ti riassume le notifiche arrivate nel frattempo;
- avvisa con il fumetto delle notifiche (solo app e mittente) e, al massimo una volta al giorno, di un'app che non usi da molto;
- tocco = apre la schermata del Navi (Parla con, Sveglia, Timer, Nota, Appuntamento, notifiche recenti, Impostazioni);
- trascinamento = lo sposti; quando lasci, cade sull'icona o sul fondo sotto di lui.

Tecnica: servizio di accessibilità `home/NaviAccessibilityService` (legge solo nome e posizione delle icone del launcher, rileva Home/app/tendina/recenti e i cambi pagina), finestre `TYPE_ACCESSIBILITY_OVERLAY` in `home/HomeNavi` (personaggio toccabile piccolo + fumetto non toccabile). Fuori dalla Home ascolta solo i cambi di finestra.

## Primo avvio (procedura guidata)
1. Benvenuto (oppure "Importa .navi").
2. Nome del Navi e tua altezza.
3. Aspetto: personaggio provvisorio già animato oppure una tua immagine. Il provvisorio si può sostituire UNA volta (Impostazioni).
4. Conferma.
5. Permessi: Navi sulla Home (accessibilità), accesso notifiche, dati di utilizzo, batteria.
   Su Android 13+ con app installate da APK, accessibilità e notifiche sono bloccate: Info app NetNavi > tre puntini > "Consenti impostazioni con restrizioni", poi riattiva.

## Impostazioni utili
- Navi sulla Home: on/off. Dimensione del Navi: 48-96 dp. Prova fumetto (compare al ritorno sulla Home).

## Limiti noti
- L'Orologio accetta solo la prossima occorrenza di un orario: per sveglie oltre domani il Navi propone un promemoria in calendario.
- Gli appuntamenti si aprono nel Calendario precompilati: il salvataggio lo confermi tu.
- L'importatore sprite non è stato provato su dispositivo con le tue strisce: tarare le soglie dopo i primi test.

- Il riconoscimento delle icone dipende dal launcher: provato in compilazione, non ancora sul Moto g32. Se il Navi non sale sulle icone, la lista è vuota: segnalalo con il nome del launcher.
