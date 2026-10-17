# Matter and SmartThings compatibility

| Function | Standards decision | Initial behavior |
|---|---|---|
| Light on/off | On/Off Light (0x0100), On/Off server | Implement first |
| Gas valve state/lock | No accurate standard gas-valve type; Door Lock would misrepresent it | Android status only; remote opening forbidden |
| Elevator call | No standard Matter elevator-call device type | Android status only; TX disabled |
| Current floor | No standard controllable floor-status device type with established SmartThings UI | Android status only |

The bridge/aggregator and Bridged Device Basic Information model is technically
available in Matter/esp-matter, but actual endpoint rendering and bridge
commissioning through a Samsung SmartThings Station need device testing. No
vendor or unrelated standard type is used to make unsupported functions appear
in SmartThings.

`HARDWARE_TEST_PENDING`: Commission the bridge with a Station, verify the light
tile, endpoint naming, subscriptions, state updates after physical wallpad
changes, fabric persistence, and behavior across OTA/rollback.

