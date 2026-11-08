# AeroChat — Neapolitan card games (Scopa, Scopone scientifico, Briscola)

Shared specification for iOS (`/Users/stefano/Documents/iOS/AeroChat`) and Android
(`/Users/stefano/Documents/Android/AeroChat`). **Both platforms must implement exactly this**, so that an
iPhone and an Android phone can play the same match. Where this spec is silent, mirror the existing
Tic-tac-toe / Battleship implementation (`GameService`, `GameBubble`).

## 1. Games

| Game | `GameKind` | Players | Where | Ends |
|---|---|---|---|---|
| Scopa | `SCOPA = 3` | 2 | direct (1:1) chat | first to **11** points (multiple hands) |
| Scopone scientifico | `SCOPONE = 4` | 4, two teams | group chat, inviter picks 3 group members | first team to **11** points (multiple hands) |
| Briscola | `BRISCOLA = 5` | 2 | direct chat | one hand (120 card points) |

## 2. Wire format (backward compatible additions to `aeromesh_ext.proto`)

```proto
enum GameKind {
  GAME_KIND_UNSPECIFIED = 0;
  TIC_TAC_TOE = 1;
  BATTLESHIP = 2;
  SCOPA = 3;
  SCOPONE = 4;
  BRISCOLA = 5;
}
enum GameAction {
  ... existing 0..5 ...
  DEAL = 6;     // carte: il mazziere distribuisce una nuova mano (deck = ordine delle 40 carte)
}
message GameMove {
  ... existing fields 1..8 ...
  bytes deck = 9;              // DEAL: 40 byte, permutazione degli id carta 0..39 (vedi §3)
  bytes captured = 10;         // MOVE (Scopa/Scopone): id delle carte prese dal tavolo; vuoto = nessuna presa
  repeated bytes players = 11; // INVITE Scopone: 4 node_id (8 byte) in ordine di posto 0..3; posto 0 = chi invita
}
```

- Old app versions receive unknown enum values as `UNRECOGNIZED` and ignore the move (they only accept
  `TIC_TAC_TOE`/`BATTLESHIP` invites) — verify this stays true.
- iOS: `AeromeshExt.pb.swift` is maintained by hand (no protoc available); add the new cases/fields with
  exactly the layout protoc-gen-swift would generate (field numbers, `_protobuf_nameMap`, decode/traverse,
  `==`). Android: edit `app/src/main/proto/aeromesh_ext.proto` (generated code).
- Field usage per action (fields not listed stay default):
  - `INVITE`: `game_id`, `kind`, `sequence = 0`, Scopone also `players`.
  - `ACCEPT`: `game_id`, `kind`, `sequence` (see §6).
  - `DEAL`: `game_id`, `kind`, `sequence`, `deck` (40 bytes), `cell` = dealer seat index.
  - `MOVE`: `game_id`, `kind`, `sequence`, `cell` = card id played, `captured` = card ids taken (Scopa/Scopone only).
  - `RESIGN`: as today (`result = 2`). In Scopone a resignation ends the match: the resigning player's team loses.
- `result` on the last `MOVE` of a match: 1 = sender's side wins, 2 = sender's side loses, 3 = draw. Receivers
  must nevertheless compute the result themselves (it is informational only).

## 3. Cards

- Card id `0..39` = `suit * 10 + (rank - 1)`.
- Suits (index): `0 = denari`, `1 = coppe`, `2 = spade`, `3 = bastoni`.
- Ranks `1..10`: 1 = Asso, 2..7 numbers, 8 = Fante, 9 = Cavallo, 10 = Re.
- Scopa/Scopone **capture value** = rank (1..10).
- Primiera value: 7→21, 6→18, 1→16, 5→15, 4→14, 3→13, 2→12, Fante/Cavallo/Re→10.
- Briscola **strength** (high→low): Asso, 3, Re, Cavallo, Fante, 7, 6, 5, 4, 2. **Points**: Asso 11, 3 10,
  Re 4, Cavallo 3, Fante 2, others 0 (total 120).
- Symbols used in this document and in UI move descriptions: ♦ = denari, ♥ = coppe, ♠ = spade, ♣ = bastoni.
- Italian names for UI: "Asso", "Fante", "Cavallo", "Re"; suits "Denari", "Coppe", "Spade", "Bastoni";
  "Settebello" = 7 di denari.

## 4. Dealing (deterministic from `deck`)

The dealer's device builds a uniformly random permutation of 0..39 (`SystemRandomNumberGenerator` /
`SecureRandom`) and sends it in `DEAL`. All devices then deal identically from `deck[0]` onward:

- Seats: 2-player games: seat 0 = inviter, seat 1 = invitee. Scopone: seat i = `players[i]`. Teams in
  Scopone: seats {0, 2} vs {1, 3}. Turn order is seat order `0 → 1 → 2 → 3 → 0` (or `0 → 1 → 0`).
- "Starting from the player after the dealer" = seat `(dealer + 1) % n`; that player also leads/plays first.
- **Scopa**: give 3 cards to each player one at a time in turn order starting after the dealer
  (i.e. `deck[0]` → player after dealer, `deck[1]` → next, …), then 4 cards face up on the table
  (`deck[6..9]`). When all players' hands are empty and cards remain, deal 3 more each the same way
  (no table cards). The hand ends when the deck and all hands are empty.
- **Scopone scientifico**: 10 cards to each player, dealt one at a time in turn order starting after the
  dealer (`deck[k]` → seat `(dealer + 1 + k) % 4`). Table starts empty.
- **Briscola**: 3 cards each one at a time starting after the dealer (`deck[0..5]`), then `deck[6]` is the
  face-up **briscola** card (its suit is trump); it is placed at the bottom of the stock, so the stock is
  `deck[7..39]` followed by `deck[6]` (drawn last). After each trick: the trick winner draws first, then the
  other player, while the stock is not empty.
- **The inviter (seat 0) deals the first hand.** Next hand's dealer = seat `(dealer + 1) % n` (the dealer
  rotates in turn order).
- The device of the new dealer sends `DEAL` automatically as soon as it has applied the last move of the
  previous hand (and the match isn't over). The first `DEAL` is sent by the inviter once all players accepted.

## 5. Rules

### Scopa / Scopone capture rules (identical)
1. On their turn a player plays one card from hand.
2. If one or more table cards have the **same value** as the played card, the player must capture exactly
   one of them (player chooses which, if several) — combinations are not allowed in that case.
3. Otherwise, if one or more **combinations** of 2+ table cards sum to the played card's value, the player
   must capture one of them (player chooses if several).
4. Otherwise the played card is placed on the table.
5. Captured cards + the played card go to the player's (team's) capture pile.
6. **Scopa** (sweep): the capture empties the table → +1 point, **except** on the last card played in the hand
   (the very last play of the hand never counts as a scopa).
7. At the end of the hand, remaining table cards go to the side that made the **last capture** (not a scopa).
   If nobody captured, they are discarded.
- Legal-capture validation (both on send and on receive): `captured` must be one of the legal options per
  rules 2–3 (as a set of card ids, order irrelevant); empty only if no option exists.

### Scopa / Scopone hand scoring (per side; Scopone side = team)
- **Carte**: 1 point to the side with more cards (> 20). 20–20: nobody.
- **Denari**: 1 point to the side with more denari (> 5). 5–5: nobody.
- **Settebello**: 1 point to the side holding the 7 of denari.
- **Primiera**: for each side, take the highest primiera value per suit; if a side lacks a suit its primiera is
  0 (invalid). The side with the higher total gets 1 point; equal totals (or both invalid): nobody.
- **Scope**: 1 point per scopa.
- Match: add to running totals. If after a hand at least one side has ≥ 11 and totals differ, the higher
  total wins. If both ≥ 11 and equal, play another hand (repeat until they differ).

### Briscola
- Leader plays any card, follower plays any card (no obligation to follow suit or to trump).
- Trick winner: if exactly one trump was played → its player; if both trumps → higher strength trump;
  otherwise → higher strength card **of the led suit** (a non-trump off-suit follower always loses).
- Winner takes both cards (adds their points), draws first, leads next.
- End when hands and stock are empty: > 60 points wins, 60–60 draw.

## 6. Sequencing, sync and robustness

- `sequence` is a **per-game global counter**. Every DEAL and MOVE uses `previous + 1`. Each device applies
  game moves strictly **in order**: a move with `sequence == state.sequence + 1` is applied; a move with a
  higher sequence (≤ `state.sequence + maxSequenceGap`) is **buffered** in the game state and applied once the
  missing ones arrive (store-and-forward guarantees eventual delivery); lower/duplicate sequences are ignored.
- `ACCEPT`: 2-player games as today (`sequence = 1`). Scopone: invitee at seat i sends ACCEPT with
  `sequence = 0` and is identified by the sender `node_id`; accepts are tracked as a set of seats and do not
  advance the counter. The inviter sends the first `DEAL` (`sequence = 1`) when all 3 accepted.
- Only the expected sender is accepted: MOVE from the node at the seat whose turn it is; DEAL from the
  expected dealer; anything else is ignored. The played card must be in that player's hand (all devices know
  all hands from `deck`, but the UI shows only your own); `captured` must be legal; otherwise ignore the move.
- Spectators: in a Scopone group, members not in `players` get a read-only bubble (they may watch scores and the
  table) and never send moves.
- Persist the full card-game state in `GameSessionState` (JSON in `messages.game_state`): hands, table,
  capture piles, scope counts, last capturer, stock (Briscola), dealer seat, turn seat, running scores,
  per-hand score breakdown of the last completed hand, buffered moves. Use the same JSON keys on both
  platforms (local data only, but keep them aligned).
- Resign: `RESIGN` from a player ends the match for everyone; his side loses.

## 7. UI (both platforms, same layout and wording)

- Composer games menu: add "Scopa", "Briscola" in direct chats; in group chats with ≥ 4 members (including me)
  show "Scopone" (and only Scopone — the 2-player games remain direct-chat only); choosing it opens a picker
  to select exactly 3 other members (seat order = selection order; partner = 2nd selected → seat 2).
- Chat bubble (compact, like the other games): game title, players/teams, running score, status line
  ("Tocca a te", "Tocca a <nome>", "Hai vinto!", "Hai perso", "Pareggio", "Invito inviato", Accetta button for
  invitees), and an "Apri tavolo" button that opens the **full-screen table**.
- Full-screen table: green felt background (`#0B5D3B`-ish, OLED-friendly dark green), opponents at top (and
  sides in Scopone) with face-down card counts and capture-pile counts, table cards in the middle, your hand at
  the bottom (tap to select, tap again or a "Gioca" button to play). If several legal captures exist, after
  choosing the card show the options (highlight cards on the table; tap a set / list of sets) and confirm.
  Briscola: show the briscola card + remaining stock count, the current trick, points. Show the last move
  ("Marco ha preso 3♦ + 4♣ con 7♦ — Scopa!"), scope markers, and at the end of each hand a score sheet
  (carte, denari, settebello, primiera, scope, totals). Resign button with confirmation.
- Card design (drawn in code, identical on both platforms): rounded rect, ivory background, rank label at
  top-left & bottom-right ("A", "2"…"7", "F", "C", "R"), central suit symbol in suit colour — denari: gold coin
  (concentric circles); coppe: red/orange cup; spade: blue sword; bastoni: green/brown club. Figures (F/C/R) get
  a larger stylised emblem + the letter. Card back: dark red/navy pattern with the AeroChat accent. Cards are
  vector-drawn (SwiftUI `Canvas`/`Path`, Compose `Canvas`), no image assets.
- Haptics on play/capture; a "Scopa!" highlight animation.
- Notifications: a move to a card game uses the existing game notification path ("🃏 Scopa", etc.).
- Strings: Italian source. Android: all 11 `strings_*.xml` locales. iOS: Italian literals in code (Xcode
  extracts them); list every new iOS key with translations (da, de, en, es, fr, nb, nl, pl, pt-PT, sv) in the
  final report — do NOT edit `Localizable.xcstrings` directly (Xcode rewrites it).

## 8. Shared test vectors (both platforms must have these exact tests)

Define in tests a helper that builds a state from an explicit `deck`. Vectors:

1. **Card ids**: `7 di denari` = 6; `Re di bastoni` = 39; `Asso di coppe` = 10.
2. **Capture options**: table ids `[1 (2 di denari), 34 (5 di bastoni), 22 (3 di spade), 13 (4 di coppe)]`; playing `5 di denari (4)` → options
   `[[34]]` only (same value beats combinations). Playing `7 di spade (26)` → options `[[1, 34], [13, 22]]` (canonical order, §8.7).
   Playing `Re di denari (9)` → `[[1, 22, 34]]` (2 + 3 + 5 = 10). Playing `Asso di spade (20)` → `[]`.
3. **Scopa last-play rule**: a capture that empties the table on the final play of the hand is not a scopa.
4. **Primiera**: side A with `7♦ 7♣ 6♠ 1♥(coppe asso)` best = 21+21+18+16 = 76; side B missing bastoni → 0.
5. **Briscola trick**: trump = coppe; lead `Asso di spade (20)`, follow `2 di coppe (11)` → follower wins;
   lead `3 di denari (2)`, follow `Re di denari (9)` → leader wins (3 > Re); lead `Re di denari (9)`,
   follow `Asso di spade (20)` (off-suit, not trump) → leader wins.
6. **Deal**: deck = identity `[0, 1, …, 39]`, Scopa, dealer seat 0: seat 1 gets `[0, 2, 4]`, seat 0 gets
   `[1, 3, 5]`, table `[6, 7, 8, 9]`. Scopone dealer 0: seat 1 gets `[0, 4, 8, …, 36]`, seat 2 `[1, 5, …]`,
   seat 3 `[2, 6, …]`, seat 0 `[3, 7, …, 39]`. Briscola dealer 0: seat 1 `[0, 2, 4]`, seat 0 `[1, 3, 5]`,
   briscola card 6 (trump denari), stock `[7…39, 6]`.
7. **Full scripted Scopa hand**: from an identity deck, a deterministic bot (always plays the first card of
   its hand, chooses the first legal capture option in canonical order: options sorted by ascending
   lexicographic order of sorted card ids) plays a full hand; assert final per-side scores
   (carte, denari, settebello, primiera, scope) — compute the expected numbers once on iOS, then copy the same
   expected values into the Android test so both engines are proven identical. Do the same for Scopone and
   Briscola.
