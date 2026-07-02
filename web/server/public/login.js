const form = document.getElementById('pin-form');
const pinInput = document.getElementById('pin');
const errorEl = document.getElementById('error');

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  errorEl.hidden = true;
  const pin = pinInput.value.trim();
  if (!pin) return;

  const res = await fetch('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pin }),
  });

  if (res.ok) {
    const params = new URLSearchParams(window.location.search);
    window.location.href = params.get('next') || '/';
    return;
  }

  errorEl.textContent = res.status === 429
    ? 'För många försök. Vänta en stund och försök igen.'
    : 'Fel kod. Försök igen.';
  errorEl.hidden = false;
  pinInput.value = '';
  pinInput.focus();
});
