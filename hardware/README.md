# Hardware safety and power checklist

## Confirmed from published material

The expansion-board schematic shows a TPS54302 buck stage from the 12 V input
rail to 5 V, a TP8485E-compatible half-duplex transceiver, tied DE and /RE, a
selectable 120 ohm termination resistor, and a 5 V IN/OUT switch. The XIAO C6
pin map maps D4/D5/D2 to GPIO22/GPIO23/GPIO2.

Do not treat the presence of the buck stage as proof that an apartment wallpad
auxiliary rail is safe or sufficiently rated.

## Bench checklist before installation

1. Disconnect the bridge. Measure the nominal wallpad rail with a CAT-rated
   meter: polarity, DC average, AC ripple, startup overshoot, and brownouts
   during door unlock, intercom, elevator call, and all lights switching.
2. Record minimum/maximum voltage for at least 24 hours. Confirm it remains
   inside the actual expansion-board input rating and has current headroom.
3. Identify protective elements on the exact PCB revision. The published
   schematic is not evidence of surge, reverse-polarity, fuse, or isolation
   performance suitable for the installation.
4. With power removed, confirm continuity: D4 to transceiver DI, D5 to RO, D2
   to tied DE+/RE, XIAO 5V to buck output, and signal ground to RS485 ground.
5. Set the 5 V switch deliberately. Never connect USB and an externally driven
   5 V rail until back-power behavior is measured for the board revision.
6. Measure A/B idle differential and common-mode voltage with a high-impedance
   differential probe. Confirm A/B labels; vendors sometimes invert naming.
7. Connect signal ground only after checking ground potential difference.
   This board is not galvanically isolated.
8. Inspect existing bus termination. Enable the on-board 120 ohm resistor only
   at a physical bus end and only when the resulting resistance is correct.
9. Begin with receive-only firmware and a current-limited bench supply. Compare
   captures with the wallpad disconnected/connected before considering TX.
10. If rail quality, protection, thermal rise, or current margin is inadequate,
    use a separately fused, protected, appropriately rated isolated supply or
    converter; document the selected part and grounding plan.

## Pending physical checks

- Exact expansion board PCB revision and component population.
- TPS54302 input limits, thermal margin, and transient protection in situ.
- Stable 12 V rail behavior and available current.
- D4/D5 continuity and D2 active-high behavior.
- Bias and termination topology of the installed RS485 network.

