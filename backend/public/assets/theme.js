// Tema web Everest: 'light' (bawaan, nuansa putih-oranye), 'dark', atau 'system' (ikuti perangkat).
// Dimuat di <head> tanpa defer agar tidak ada kilatan warna saat halaman dibuka.
// ?theme=dark|light di URL (dipakai aplikasi Android) menimpa pilihan tanpa menyimpannya.
(function () {
  var KEY = 'everest-theme';
  var ORDER = ['light', 'dark', 'system'];
  var LABEL = { system: 'Tema: sistem', light: 'Tema: terang', dark: 'Tema: gelap' };
  var forced = new URLSearchParams(location.search).get('theme');
  var current = 'light';
  try { current = localStorage.getItem(KEY) || 'light'; } catch (e) { /* penyimpanan diblokir */ }
  function stored() { return current; }
  function apply(mode) {
    var root = document.documentElement;
    if (mode === 'light' || mode === 'dark') root.setAttribute('data-theme', mode);
    else root.removeAttribute('data-theme');
  }
  apply(forced || stored());
  document.addEventListener('DOMContentLoaded', function () {
    var buttons = document.querySelectorAll('[data-theme-toggle]');
    function label() { buttons.forEach(function (b) { b.textContent = LABEL[stored()] || LABEL.light; }); }
    label();
    buttons.forEach(function (b) {
      b.addEventListener('click', function () {
        var next = ORDER[(ORDER.indexOf(current) + 1) % ORDER.length];
        current = next;
        try { localStorage.setItem(KEY, next); } catch (e) { /* mode privat: hanya berlaku di halaman ini */ }
        forced = null;
        apply(next);
        label();
      });
    });
  });
})();
