import { CheckIcon } from './icons';

// Web port of the Android BoetCheckbox (ui/list/ListScreen.kt): 26px rounded
// box, Stone border when unchecked, Moss fill + white check when checked,
// with a press dip and a popping checkmark. Styles in styles/recipes.css.
export default function BoetCheckbox({
  checked,
  onToggle,
  label,
}: {
  checked: boolean;
  onToggle: () => void;
  label: string;
}) {
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={checked}
      aria-label={label}
      className={`boet-checkbox${checked ? ' checked' : ''}`}
      onClick={onToggle}
    >
      <span className="boet-checkbox-mark">
        <CheckIcon />
      </span>
    </button>
  );
}
