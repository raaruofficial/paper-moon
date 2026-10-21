# AC-11 FLIP DELTAS — staged, NOT applied (owner-gated)

Prepared at AC-10 seal (2026-10-21) so the flip is a reviewable
act, not an improvisation. **Nothing in this document has been
executed.** The flip itself — and each leg of it — happens only
on the owner's word, in the owner's presence for the wallet acts.

State being flipped FROM (sealed, AC-10): `SALES_ENABLED=false`,
receiving wallet configured (dedicated), Terms a DRAFT, listing
still carrying the free-candidate posture.

## Delta 1 — the code flip (one line)

File: `app/src/main/java/com/hindsight/app/money/MoneyConfig.kt`

```diff
-    private const val SALES_ENABLED: Boolean = false
+    private const val SALES_ENABLED: Boolean = true
```

Effect: `railOpen` becomes true (wallet already configured).
Nothing else in code changes. This commit is made on
`money-build-v0.2.0`, harness + census re-run, CI green, and is
the ONLY code delta of the flip. Pre-flip re-verifications, same
sitting: receiving-wallet balance watch live, sweep path to the
publisher wallet rehearsed with a dust amount from a test wallet
(owner's hands), first real purchase is the owner's own test buy
before any public traffic is pointed at it.

## Delta 2 — Terms adoption mechanics

File: `docs/TERMS_OF_USE.md`

1. Replace `**Effective date:** `[EFFECTIVE_DATE]`` with the
   flip date (the date the rail opens, Pacific).
2. Remove the `> **DRAFT** …` header block; replace with a one-
   line status: adopted, effective as dated, all sales final,
   grant as written in §2.
3. Confirm the in-app legal surface shows the adopted text (the
   bundle/native legal block reads the same instrument — no
   second copy may drift from it).
4. Record adoption in the store release ledger the same day.

## Delta 3 — the listing leg (store-side, owner's portal acts)

From PM_MONEY_BUILD_PREP B-6 (nothing here is agent-executable):

1. **Data-safety answers change on the paid build** — re-run in
   the portal in the Act A/B sitting (reviewer, engineering
   present): identity, wallet-linkage, purchase and ledger data
   the free build never collected.
2. **Age rating re-run** — gambling-adjacent questionnaire
   answers change now that real money moves.
3. **"Charges nothing" family retires** the moment money ships:
   listing copy, screenshots, and review notes are re-captured
   from the PAID build (pay surface visible), and every
   free/simulated claim comes out of the copy.
4. Support/privacy surfaces already live for HAUNT are mirrored
   for PAPER MOON before submission, not after.

## Flip order (no step out of sequence)

1. Owner word + owner present. 2. Delta 2 (Terms adopted,
dated). 3. Delta 1 commit + harness + census + CI. 4. Owner's
test purchase end-to-end (buy → settlement journal → grant →
ride → sweep check). 5. Delta 3 listing acts in the portal
(owner). 6. Submission. Any failure at step 4 reverts Delta 1
(single-line revert) and the rail is dark again — the revert is
as small as the flip by design.
