# Tracker Android - Material 3 Expressive Design System

## 1. Brand Personality
Privacy-first, fully local location & activity tracker for Android. Reliable, secure, modern, and slightly adventurous.

## 2. Color Palette
Balances trust and security (deep, reliable cool tones) with adventure and activity (vibrant, energetic warm tones).

### Primary: Secure Teal
*   **Light Mode:** Primary: `#006874`, On-Primary: `#FFFFFF`, Primary Container: `#97F0FF`, On-Primary Container: `#001F24`
*   **Dark Mode:** Primary: `#4FD8EB`, On-Primary: `#00363D`, Primary Container: `#004F58`, On-Primary Container: `#97F0FF`

### Secondary: Trail Slate
*   **Light Mode:** Secondary: `#4A6367`, On-Secondary: `#FFFFFF`, Secondary Container: `#CDE7EC`, On-Secondary Container: `#051F23`
*   **Dark Mode:** Secondary: `#B1CBD0`, On-Secondary: `#1C3438`, Secondary Container: `#334B4F`, On-Secondary Container: `#CDE7EC`

### Tertiary: Sunset Rust (Expressive Accent)
*   **Light Mode:** Tertiary: `#98483A`, On-Tertiary: `#FFFFFF`, Tertiary Container: `#FFDAD4`, On-Tertiary Container: `#3C0903`
*   **Dark Mode:** Tertiary: `#FFB4A8`, On-Tertiary: `#5C190D`, Tertiary Container: `#7A3024`, On-Tertiary Container: `#FFDAD4`

### Neutral / Surface (Tinted with Primary)
*   **Light Mode:** Surface: `#F8FDFF`, Surface Container: `#EBF4F6`, On-Surface: `#171D1E`, Outline: `#6F797A`
*   **Dark Mode:** Surface: `#0E1415`, Surface Container: `#1A2022`, On-Surface: `#DFE4E5`, Outline: `#899294`

### Semantic Colors
*   **Error:** Light: `#BA1A1A` / `#FFDAD6` | Dark: `#FFB4AB` / `#93000A`
*   **Success:** Light: `#146C2E` / `#A3F4A5` | Dark: `#88D78A` / `#00531E`
*   **Warning:** Light: `#8D5000` / `#FFDCC1` | Dark: `#FFB776` / `#6B3D00`

### Contextual Colors
*   **Track Active:** `#FF3B30`
*   **Track History:** `#00829B`

## 3. Typography Scale
*   **Primary Typeface (Display, Headline, Title):** Outfit (Google Fonts)
*   **Secondary Typeface (Body, Label):** Inter (Google Fonts)
*   **Feature:** Tabular Figures (`tnum`) for all numeric data.

### Scale
*   **Display Large:** Outfit | Bold (700) | 64sp | LH: 72sp | LS: -0.25sp
*   **Display Medium:** Outfit | Bold (700) | 52sp | LH: 60sp | LS: -0.25sp
*   **Display Small:** Outfit | Bold (700) | 44sp | LH: 52sp | LS: 0sp
*   **Headline Large:** Outfit | SemiBold (600) | 36sp | LH: 44sp | LS: 0sp
*   **Headline Medium:** Outfit | SemiBold (600) | 32sp | LH: 40sp | LS: 0sp
*   **Headline Small:** Outfit | SemiBold (600) | 28sp | LH: 36sp | LS: 0sp
*   **Title Large:** Outfit | Medium (500) | 22sp | LH: 28sp | LS: 0sp
*   **Title Medium:** Outfit | Medium (500) | 18sp | LH: 24sp | LS: 0.15sp
*   **Title Small:** Outfit | Medium (500) | 14sp | LH: 20sp | LS: 0.1sp
*   **Body Large:** Inter | Regular (400) | 16sp | LH: 24sp | LS: 0.5sp
*   **Body Medium:** Inter | Regular (400) | 14sp | LH: 20sp | LS: 0.25sp
*   **Body Small:** Inter | Regular (400) | 12sp | LH: 16sp | LS: 0.4sp
*   **Label Large:** Inter | Medium (500) | 14sp | LH: 20sp | LS: 0.1sp
*   **Label Medium:** Inter | Medium (500) | 12sp | LH: 16sp | LS: 0.5sp
*   **Label Small:** Inter | Medium (500) | 11sp | LH: 16sp | LS: 0.5sp (All Caps for technical metadata)

## 4. Motion System
*   **SecureSnap:** StiffnessMedium (~1500), DampingRatioNoBouncy (1.0). Fast, definitive, stable. Used for privacy toggles, core navigation.
*   **TactileActive:** StiffnessMediumLow (~400), DampingRatio 0.65f. Energetic, responsive. Used for primary actions (Start tracking FAB).
*   **SpatialGlide:** StiffnessLow (~200), DampingRatio 0.8f. Smooth, sweeping. Used for bottom sheets, map overlays.
*   **Durations:** Micro (150ms), Short (250ms), Medium (400ms), Long (600ms).

## 5. Shape System
*   **Waypoint (FAB):** Asymmetrical. Top-left, top-right, bottom-left: 50% radius. Bottom-right: 10% radius.
*   **Momentum Pill (Buttons/Chips):** Elongated stadium. Leading edge: 50% radius. Trailing edge: 20% radius.
*   **Terrain Card (Cards):** Opposing corner symmetry. Top-left, bottom-right: 15% radius. Top-right, bottom-left: 4% radius.

## 6. Component Specifications
*   **Map Screen:** Waypoint FAB (Secure Teal, TactileActive). Terrain Card for metrics overlay (Inter tabular figures).
*   **Statistics Screen:** Terrain Cards for trips. Momentum Pill for activity chips. Tinted surfaces. Outfit for month headers.
*   **Trip Detail Screen:** Terrain Cards for data segments. Sunset Rust for peak metrics. Outfit for trip title.
*   **Settings/Privacy Screen:** Momentum Pill for toggles. SecureSnap motion. Outfit for headers, Inter for explanations.
