// Mirrors android/app/src/main/java/se/jabba/boet/data/remote/Dtos.kt — keep in
// sync with the backend's actual response shapes (server/src/routes/*.js), not
// with these comments.

export interface BoetList {
  id: string;
  name: string;
  kind: string;
  icon?: string | null;
  position: number;
  archived: boolean;
  sortPrompt?: string | null;
  bgImageUrl?: string | null;
  bgBlur?: number | null;
  bgOverlay?: number | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface Category {
  id: string;
  listId: string;
  name: string;
  icon?: string | null;
  position: number;
}

export interface Item {
  id: string;
  listId: string;
  categoryId?: string | null;
  name: string;
  quantity?: string | null;
  note?: string | null;
  checked: boolean;
  position: number;
  addedBy?: string | null;
  modifiedBy?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface Favorite {
  id: string;
  name: string;
  categoryName?: string | null;
  position?: number;
  updatedAt?: string;
}

export interface RecipeIngredient {
  id: string;
  quantity?: number | null;
  unit?: string | null;
  food: string;
  display: string;
  note?: string | null;
  sections: string[];
}

export interface RecipeStep {
  id: string;
  text: string;
  ingredientRefs: string[];
  timerSeconds?: number | null;
  title?: string | null;
}

export type AiStatus =
  | 'queued'
  | 'parsing_cloud'
  | 'parsing_local'
  | 'translating'
  | 'degraded'
  | 'done'
  | 'error';

export interface RecipeDoc {
  name: string;
  description?: string | null;
  image?: string | null;
  servings?: number | null;
  totalTime?: string | null;
  sourceUrl?: string | null;
  youtubeUrl?: string | null;
  instagramUrl?: string | null;
  ingredients: RecipeIngredient[];
  steps: RecipeStep[];
  aiStatus?: AiStatus | null;
  aiError?: string | null;
}

export interface Recipe {
  id: string;
  name: string;
  image?: string | null;
  data: RecipeDoc;
  categoryName?: string | null;
  position?: number | null;
  selected?: boolean;
  sourceKey?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface Member {
  id: string;
  name: string;
}

export interface MealSummary {
  id: string;
  name: string;
  thumb: string | null;
}

export interface MealDetail {
  id: string;
  name: string;
  category: string | null;
  area: string | null;
  thumb: string | null;
  tags: string[];
  youtube: string | null;
  instructions: string;
  ingredients: Array<{ measure: string; food: string }>;
}

export interface Bootstrap {
  household: { id: string; name: string; created_at: string } | null;
  members: Member[];
  lists: BoetList[];
  categories: Category[];
  items: Item[];
  learned: Array<{ key: string; category: string }>;
  favorites: Favorite[];
  recipes: Recipe[];
}
