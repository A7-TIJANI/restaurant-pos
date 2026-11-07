// Static shell only (roadmap weeks 1-2): live clock + visual Cash/MoMo toggle.
// Cart, printing and finalization arrive with the order flow (weeks 3-4).
(function () {
    'use strict';

    function tick() {
        const text = new Date().toLocaleString();
        const clock = document.getElementById('clock');
        const printDate = document.getElementById('print-date');
        if (clock) clock.textContent = text;
        if (printDate) printDate.textContent = text;
    }
    tick();
    setInterval(tick, 1000);

    document.querySelectorAll('.pay-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            document.querySelectorAll('.pay-btn').forEach(function (b) { b.classList.remove('active'); });
            btn.classList.add('active');
        });
    });
})();
