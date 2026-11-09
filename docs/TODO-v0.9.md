# NetNavi 0.9 "custode"

## Fatto
- Impostazioni > "Permessi e accesso": tutti i permessi in un pannello, con stato (verde/rosso/giallo), spiegazione semplice e un solo pulsante "Attiva" ciascuno; interruttore "Vedi il Navi sulla Home"; aiuto passo passo per "Impostazione con restrizioni". Il riquadro "Mancano N permessi" della dashboard apre questo pannello.
- Dimensione del Navi sulla Home: 48-256 dp, scorciatoie 64/96/128/192/256, indicazione dei pixel reali.
- "Cambia aspetto del Navi": nuova immagine e altezza quando vuoi, classe ricalcolata (corporatura automatica o scelta a mano), livello/EXP/legame/ricordi restano; opzione per togliere le animazioni importate del vecchio aspetto.
- Immagine della scheda del personaggio modificabile quando vuoi ("Usa il busto" per tornare indietro).
- Chiamate moleste (calls/): suggerimenti del Navi dal registro chiamate (solo locale, decidi tu), black list, rifiuto silenzioso, segreteria con audio a scelta (file audio o voce del Navi) dalla 2ª/3ª/5ª chiamata in 7 giorni, ascolto 0/15/30 s con riassunto (tema, azienda, numero citato, possibile truffa). Testo mai salvato. Numero di prova 24 h. Registro delle chiamate gestite. Comando chat "chiamate moleste"/"black list".
- Test: CallGuardTest (normalizzazione, decisione, suggerimenti, riassunto).

## Limiti noti
- L'audio della segreteria esce dal vivavoce: Android non consente di immetterlo nella linea.
- Il filtro di sistema (ruolo ID chiamante e spam) di solito non vede i contatti in rubrica: per loro il telefono squilla un istante, poi interviene CallStateReceiver.
- Il riassunto dipende dal riconoscimento vocale offline (Android 13+) e dall'accesso al microfono durante la chiamata: se non disponibile, solo la durata del parlato.

## Da verificare sul Moto g32
- acceptRingingCall/endCall con l'interfaccia Motorola.
- Qualità del riconoscimento durante la chiamata in vivavoce.

## 0.9.1 - Parlare ed espressioni
- Problema: la riga 15 "Parlare ed espressioni" (8 frame: parla, sorpreso, pensa, saluta) non era in ciclo e veniva riprodotta tutta mentre il Navi parlava, quindi mostrava anche sorpresa, riflessione e saluto. Le IA di immagini inoltre generano male strisce con azioni miste.
- Soluzione: riga 15 = solo "Parlare" (4 frame, ciclo, master). Nuove righe 25 "Sorpreso" (4) e 26 "Saluta" (6); "Riflette" resta la 23. Prompt riscritti, una sola azione per riga.
- Migrazione: una vecchia riga 15 da 8 frame, già salvata o importata ora, viene divisa da sola (15 parla, 25 sorpreso, 23 pensa se vuota).
- Uso: sorpreso all'arrivo di una notifica, saluta quando torni sulla Home e nella schermata di benvenuto, pensieroso quando riflette.
- Con questo la fase 2 è chiusa, se le prove sul telefono vanno a buon fine.
