# Illustrated theme assets

Eight original backgrounds were generated with the built-in imagegen tool for four light/dark theme pairs. They are bundled locally; the application does not download theme images or send theme choices to an image service. Coil samples and caches requested images off the UI thread.

Final assets live in `V2rayNG/app/src/main/res/drawable-nodpi/`:

| Pair | Light asset | Dark asset |
| --- | --- | --- |
| Kyoto | `dr_theme_kyoto_light.png` | `dr_theme_kyoto_dark.png` |
| Baikal | `dr_theme_baikal_light.png` | `dr_theme_baikal_dark.png` |
| Guilin | `dr_theme_jade_light.png` | `dr_theme_jade_dark.png` |
| Porcelain | `dr_theme_silk_light.png` | `dr_theme_silk_dark.png` |

## Prompt set

Shared specification: one full-bleed portrait 2:3 mobile app backdrop, original refined painterly editorial illustration. Keep the left 70 percent and central 75 percent quiet and low contrast for legible app controls. Concentrate details near the right edge and bottom. No text, logos, interface, borders, or abstract geometric graphics.

- Kyoto dark: Japanese Kyoto night garden; authentic wooden temple roof and maple branches at the far right, misty mountain and still water near the bottom. Almost-black indigo and muted crimson, restrained lighting.
- Kyoto light: Kyoto garden at dawn; wooden temple roof and sakura tree at the right, warm ivory mist, dusty rose and distant mountain.
- Baikal dark: Russian Lake Baikal under northern lights; Siberian pine at the right, distant snowy mountain, midnight blue ice and tiny amber cabin lights.
- Baikal light: Lake Baikal at sunrise; Siberian pine at the right, snowy mountain, pearl white ice and muted azure blue.
- Guilin dark: Chinese Guilin landscape with traditional pavilion at the right; layered roof, ink mountains in emerald night mist and dim antique gold lanterns.
- Guilin light: Guilin landscape and pavilion at the right; intricate roof, ink mountains in pale celadon and ivory morning mist.
- Porcelain dark: Chinese porcelain still life; plum blossoms and an authentic ceramic vase at the right, midnight sapphire and subdued gold texture.
- Porcelain light: Chinese porcelain still life; plum blossoms and a blue-and-white vase at the right, warm ivory rice paper and restrained cobalt.

The application adds a theme-colored scrim behind controls. Previews show the unshaded illustration. Existing palette IDs remain unchanged; the added stable IDs are `kyoto`, `baikal`, `jade`, and `porcelain`.
