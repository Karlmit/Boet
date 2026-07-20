import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useBoetStore } from '../state/store';
import { useAuth } from '../state/auth';
import { api } from '../api/client';
import { getIdentity, displayName } from '../state/identity';
import { groupByTags, ingredientLine, chipLabel, addQty, fmtNum } from '../lib/recipe';
import { useWakeLock } from '../hooks/useWakeLock';
import BoetCheckbox from '../components/BoetCheckbox';
import { CategorySelect } from '../components/CategorySelect';
import {
  RestaurantIcon,
  LightbulbIcon,
  PushPinIcon,
  AutoAwesomeIcon,
  EditIcon,
  DeleteIcon,
  CartIcon,
  AddCartIcon,
  PlayCircleIcon,
  LanguageIcon,
  MovieIcon,
  OpenInNewIcon,
} from '../components/icons';
import type { RecipeIngredient } from '../api/types';
import type { ReactNode } from 'react';

// Tappable source-link row, same style as the Android app's
// YoutubeLinkRow/InstagramLinkRow/SourceLinkRow (ui/common/Common.kt):
// leading icon + uppercase label + a small open-in-new glyph, all MossDeep.
function LinkRow({ href, icon, label }: { href: string; icon: ReactNode; label: string }) {
  return (
    <a className="link-row" href={href} target="_blank" rel="noreferrer">
      {icon}
      <span className="label">{label}</span>
      <OpenInNewIcon />
    </a>
  );
}

export default function RecipeDetail() {
  const { recipeId } = useParams<{ recipeId: string }>();
  const { recipes, lists, recipeCategories } = useBoetStore();
  const { authenticated } = useAuth();
  const navigate = useNavigate();
  const wakeLock = useWakeLock();

  // Transient cooking state — reset when switching recipe, never persisted
  // (matches the Android detail screen).
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [cartMode, setCartMode] = useState(false);
  const [servingsOverride, setServingsOverride] = useState<number | null>(null);
  useEffect(() => {
    setChecked(new Set());
    setCartMode(false);
    setServingsOverride(null);
  }, [recipeId]);

  const identity = getIdentity();
  const recipe = recipes.find((r) => r.id === recipeId);
  const groceryList = lists.find((l) => l.kind === 'grocery' && !l.archived) || lists.find((l) => !l.archived);

  if (!recipe) return <p className="body-text">Receptet hittades inte.</p>;
  const activeRecipe = recipe;
  const doc = activeRecipe.data;

  const baseServings = doc.servings && doc.servings > 0 ? doc.servings : 0;
  const servings = servingsOverride ?? baseServings;
  const factor = baseServings > 0 ? servings / baseServings : 1;

  function toggleChecked(id: string) {
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function addIngredientToList(ing: RecipeIngredient) {
    if (!groceryList || !identity) return;
    await api.post(`/api/lists/${groceryList.id}/items`, {
      name: ing.food,
      quantity: addQty(ing, factor) || undefined,
      addedBy: displayName(identity),
    });
  }

  async function addAllToList() {
    if (!groceryList || !identity) return;
    const items = doc.ingredients
      .filter((i) => i.food.trim())
      .map((i) => ({ name: i.food, quantity: addQty(i, factor) || undefined }));
    if (items.length === 0) return;
    await api.post(`/api/lists/${groceryList.id}/items`, { items, addedBy: displayName(identity) });
  }

  async function toggleSelected() {
    await api.post(`/api/recipes/${activeRecipe.id}/select`, { selected: !activeRecipe.selected });
  }

  // Instant-apply, like the Android chip pickers — no local state needed since
  // the store re-renders from the PATCH response/WebSocket broadcast.
  async function setTypeCategory(id: string | null) {
    await api.patch(`/api/recipes/${activeRecipe.id}`, { typeCategoryId: id });
  }
  async function setCountryCategory(id: string | null) {
    await api.patch(`/api/recipes/${activeRecipe.id}`, { countryCategoryId: id });
  }
  async function resortCategories() {
    await api.post(`/api/recipes/${activeRecipe.id}/resort-categories`);
  }

  async function remove() {
    if (!confirm(`Ta bort ${doc.name}?`)) return;
    await api.delete(`/api/recipes/${activeRecipe.id}`);
    navigate('/recipes');
  }

  const ingredientById = new Map(doc.ingredients.map((i) => [i.id, i]));
  // AI linking sometimes attaches the same ingredient to every step it's still
  // physically present in (e.g. "all ingredients in the pot") instead of just the
  // step it's first added — only show a chip the first time it's referenced.
  const firstStepForIngredient = new Map<string, number>();
  doc.steps.forEach((step, i) => step.ingredientRefs.forEach((ref) => {
    if (!firstStepForIngredient.has(ref)) firstStepForIngredient.set(ref, i);
  }));

  const ingredientGroups = groupByTags(doc.ingredients, (i) => i.sections ?? []);

  const ingredientsColumn = (
    <div className="recipe-ingredients-col">
      <div className="recipe-section-header">
        <h2 className="label">Ingredienser</h2>
        {authenticated && (
          <div className="recipe-section-controls">
            <button className="btn-ghost btn-small" onClick={addAllToList} disabled={!groceryList}>
              Lägg alla
            </button>
            <button
              className={`icon-toggle${cartMode ? ' active' : ''}`}
              onClick={() => setCartMode((m) => !m)}
              title="Lägg i inköpslistan"
              aria-pressed={cartMode}
            >
              <CartIcon />
            </button>
          </div>
        )}
      </div>
      {ingredientGroups.map(([tag, group]) => (
        <div key={tag ?? '__untagged'} className="ingredient-group">
          {tag && <div className="ingredient-group-title">{tag}</div>}
          {group.map((ing) => {
            const isChecked = checked.has(ing.id);
            return (
              <div key={`${tag ?? ''}:${ing.id}`} className={`ingredient-row${isChecked && !cartMode ? ' checked' : ''}`}>
                <span className="ingredient-dot" />
                <span className="ingredient-text body-text">{ingredientLine(ing, factor)}</span>
                {cartMode && authenticated ? (
                  <button
                    className="ingredient-add"
                    onClick={() => addIngredientToList(ing)}
                    disabled={!groceryList}
                    title="Lägg i inköpslistan"
                  >
                    <AddCartIcon />
                  </button>
                ) : (
                  <BoetCheckbox checked={isChecked} onToggle={() => toggleChecked(ing.id)} label={ing.food} />
                )}
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );

  const stepsColumn = (
    <div>
      <div className="recipe-section-header">
        <h2 className="label">Gör så här</h2>
      </div>
      {doc.steps.map((step, i) => (
        <div key={step.id} className="recipe-step">
          <span className="step-number">{i + 1}</span>
          <div className="step-body">
            {step.title && <div className="step-phase-title">{step.title}</div>}
            <p className="body-text">{step.text}</p>
            {(step.timerSeconds || step.ingredientRefs.some((ref) => firstStepForIngredient.get(ref) === i)) && (
              <div className="step-chips">
                {step.ingredientRefs.map((ref) => {
                  if (firstStepForIngredient.get(ref) !== i) return null;
                  const ing = ingredientById.get(ref);
                  if (!ing) return null;
                  return (
                    <span key={ref} className={`step-chip${checked.has(ing.id) ? ' checked' : ''}`}>
                      {chipLabel(ing, factor)}
                    </span>
                  );
                })}
                {step.timerSeconds ? <span className="step-chip timer">{Math.round(step.timerSeconds / 60)} min</span> : null}
              </div>
            )}
          </div>
        </div>
      ))}
    </div>
  );

  return (
    <div className="page-paper recipe-detail">
      <div className="recipe-actionbar">
        <Link to="/recipes" className="body-text" style={{ color: 'var(--moss-deep)', fontWeight: 600, textDecoration: 'none' }}>
          ← Recept
        </Link>
        <div className="recipe-actionbar-icons">
          {authenticated && (
            <button
              className={`icon-toggle${activeRecipe.selected ? ' active' : ''}`}
              onClick={toggleSelected}
              title={activeRecipe.selected ? 'Avmarkera köksskärm' : 'Visa på köksskärm'}
              aria-pressed={Boolean(activeRecipe.selected)}
            >
              <PushPinIcon />
            </button>
          )}
          {authenticated && (
            <button
              className={`icon-toggle${activeRecipe.categoryStatus === 'queued' ? ' active' : ''}`}
              onClick={resortCategories}
              disabled={activeRecipe.categoryStatus === 'queued'}
              title="Sortera om (typ/land)"
            >
              <AutoAwesomeIcon />
            </button>
          )}
          {wakeLock.supported && (
            <button
              className={`icon-toggle${wakeLock.active ? ' active' : ''}`}
              onClick={wakeLock.toggle}
              title="Håll skärmen vaken"
              aria-pressed={wakeLock.active}
            >
              <LightbulbIcon />
            </button>
          )}
          {authenticated && (
            <>
              <Link className="icon-toggle" to={`/recipes/${activeRecipe.id}/edit`} title="Redigera">
                <EditIcon />
              </Link>
              <button className="icon-toggle" onClick={remove} title="Ta bort">
                <DeleteIcon />
              </button>
            </>
          )}
        </div>
      </div>

      {doc.aiStatus && !['done', 'degraded'].includes(doc.aiStatus) && (
        <p className="body-text" style={{ color: 'var(--moss-deep)', marginBottom: 12 }}>
          {doc.aiStatus === 'error' ? `AI-import misslyckades: ${doc.aiError ?? ''}` : 'AI:n bearbetar receptet…'}
        </p>
      )}

      <div className="recipe-hero">
        {doc.image ? <img src={doc.image} alt="" /> : <RestaurantIcon size={48} />}
      </div>

      <div className="recipe-title-block">
        <h1 className="headline">{doc.name || 'Namnlöst recept'}</h1>
        {doc.description && <p className="body-text recipe-description">{doc.description}</p>}
        <div className="recipe-meta-row">
          {doc.totalTime && <span className="label">{doc.totalTime}</span>}
          {authenticated ? (
            <>
              <CategorySelect
                label="Typ"
                kind="type"
                options={recipeCategories.filter((c) => c.kind === 'type')}
                value={activeRecipe.typeCategory?.id ?? null}
                onChange={setTypeCategory}
              />
              <CategorySelect
                label="Land"
                kind="country"
                options={recipeCategories.filter((c) => c.kind === 'country')}
                value={activeRecipe.countryCategory?.id ?? null}
                onChange={setCountryCategory}
              />
            </>
          ) : (
            <>
              {activeRecipe.typeCategory && <span className="recipe-chip">{activeRecipe.typeCategory.name}</span>}
              {activeRecipe.countryCategory && <span className="recipe-chip">{activeRecipe.countryCategory.name}</span>}
            </>
          )}
        </div>
        <div className="recipe-link-rows">
          {doc.youtubeUrl && <LinkRow href={doc.youtubeUrl} icon={<PlayCircleIcon />} label="Se på YouTube" />}
          {doc.instagramUrl && <LinkRow href={doc.instagramUrl} icon={<MovieIcon />} label="Visa på Instagram" />}
          {/* sourceUrl is set to the same URL as instagramUrl for an Instagram
              import (routes/instagram.js) — skip the generic link so it isn't
              shown twice. Same rule as the Android detail screen. */}
          {doc.sourceUrl && !doc.instagramUrl && (
            <LinkRow href={doc.sourceUrl} icon={<LanguageIcon />} label="Visa originalreceptet" />
          )}
        </div>
      </div>

      {baseServings > 0 && (
        <div className="servings-stepper">
          <button onClick={() => setServingsOverride((prev) => Math.max(1, (prev ?? baseServings) - 1))} aria-label="Färre portioner">
            −
          </button>
          <span className="servings-count">{fmtNum(servings)} portioner</span>
          <button onClick={() => setServingsOverride((prev) => Math.min(99, (prev ?? baseServings) + 1))} aria-label="Fler portioner">
            +
          </button>
        </div>
      )}

      <div className="recipe-columns">
        {ingredientsColumn}
        {stepsColumn}
      </div>
    </div>
  );
}
