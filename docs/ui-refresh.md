# SoulBot UI refresh

## Visual system

- Material 3 day/night palettes use a restrained lavender/indigo accent, softened page surfaces, and higher-contrast text.
- Shared typography styles define page titles, section labels, supporting copy, and consistent text-input corner radii.
- Primary actions, outlined actions, cards, and navigation use a common rounded shape language.
- Bottom navigation uses local vector icons, explicit selected/unselected colors, and the Material 3 active indicator.

## Refreshed screens

- Dashboard: clearer status hierarchy, prominent runtime action, grouped permissions/configuration, and a dedicated contact-library entry.
- Contacts: simplified search header, card-based list rows, clearer identity metadata, and calmer empty state.
- Contact detail: stronger identity summary, horizontal context tabs, and a memory timeline with timestamps.
- Learning: clearer introduction and refreshed stats card, with existing feedback controls and switches retained.
- Settings: grouped navigation cards with icons and descriptive subtitles.
- Existing task, model, profile, backup, diagnostic, and log screens inherit the refreshed shared theme and text-input styles.

## Memory visibility

The contact memory timeline reads the existing contact_memories table and displays each stored fragment with its timestamp. It is intentionally read-only in this iteration. A stored fragment is not presented as a verified fact, personality assessment, or relationship score.

## Behavior boundary

This update changes visual presentation and memory observability only. It does not change the automatic memory extraction rules, memory retrieval ranking, prompt content, reply policies, model routing, or contact-relationship interpretation.
