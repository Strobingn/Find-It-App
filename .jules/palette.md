## 2025-05-18 - Compose Selection Semantics and Icon Duplication
**Learning:** In Jetpack Compose, clickable cards acting as option selectors need `role = Role.RadioButton` and `semantics { this.selected = isSelected }` so screen readers announce active option states. Furthermore, icons inside labeled clickable containers need `contentDescription = null` to prevent duplicate announcements.
**Action:** When creating custom option grids or labeled icon toolbars, always specify proper selection semantics and suppress redundant icon content descriptions.
