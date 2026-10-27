## 2025-05-18 - Descriptive Action Labels for Sheet Content Actions
**Learning:** Icon-only buttons inside ModalBottomSheet sheets (such as pin or copy actions) require explicit action-oriented content descriptions (e.g., "Pin finding to target list" instead of "Pin finding") to provide clear context for screen reader users when navigating sheet content.
**Action:** Always provide full action context in `contentDescription` for icon-only buttons in bottom sheets and overlays.
