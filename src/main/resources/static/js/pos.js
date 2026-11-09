// POS screen: cart, add-ons, payment toggle, two-step print flow and the crash-resume prompt.
// All business rules live on the server (/api/order/*); this file only renders the order it is given.
(function () {
    'use strict';

    var RESTAURANT = document.body.getAttribute('data-restaurant') || '';
    var TZ = 'Africa/Kigali'; // A13: receipts show Kigali time whatever the laptop's own zone is

    var order = null;
    var selectedLineId = null;
    var printing = false;

    function $(id) { return document.getElementById(id); }

    function el(tag, className, text) {
        var e = document.createElement(tag);
        if (className) { e.className = className; }
        if (text !== undefined && text !== null) { e.textContent = text; }
        return e;
    }

    function money(n) { return Number(n).toLocaleString('en-US') + ' RWF'; }

    function stamp() {
        return new Date().toLocaleString('en-GB', { timeZone: TZ, dateStyle: 'medium', timeStyle: 'medium' });
    }

    function toast(message, kind) {
        var t = el('div', 'toast ' + (kind || 'info'), message);
        $('toast-stack').appendChild(t);
        setTimeout(function () { t.remove(); }, 4000);
    }

    // ---- server calls -----------------------------------------------------------------------------------

    async function call(method, url, body) {
        try {
            var res = await fetch(url, {
                method: method,
                headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
                body: body !== undefined ? JSON.stringify(body) : undefined
            });
            var data = null;
            try { data = await res.json(); } catch (ignore) { /* empty body */ }
            return { ok: res.ok, status: res.status, data: data };
        } catch (e) {
            return { ok: false, status: 0, data: { message: 'Cannot reach the POS service. Is the app still running?' } };
        }
    }

    // Runs a call that returns the order; on failure shows the server's message and re-syncs with the server.
    async function act(method, url, body) {
        var r = await call(method, url, body);
        if (!r.ok) {
            toast((r.data && r.data.message) || 'Something went wrong. Please try again.', 'error');
            await refresh();
            return null;
        }
        setOrder(r.data);
        return r.data;
    }

    async function refresh() {
        var r = await call('GET', '/api/order');
        if (r.ok) { setOrder(r.data); }
        return r.ok ? r.data : null;
    }

    // Taps are processed one at a time, in order, so fast tapping can never interleave requests.
    var chain = Promise.resolve();
    function queue(fn) {
        chain = chain.then(fn).catch(function (e) { console.error(e); });
    }

    // ---- state + rendering ------------------------------------------------------------------------------

    function setOrder(o) {
        order = o;
        var ids = o.lines.map(function (l) { return l.id; });
        if (ids.indexOf(selectedLineId) === -1) {
            selectedLineId = ids.length ? ids[ids.length - 1] : null;
        }
        render();
    }

    function isDraft() { return order && order.status === 'DRAFT'; }

    function render() {
        if (!order) { return; }
        document.body.classList.toggle('locked', !isDraft());
        $('cart-order-no').textContent = '#' + order.orderNumber;
        renderCart();
        renderPayment();
        $('print-btn').disabled = !isDraft() || order.lines.length === 0 || printing;
        $('cancel-btn').hidden = !isDraft() || order.lines.length === 0;
        renderReceipts();
    }

    function renderCart() {
        var list = $('cart-items-list');
        list.textContent = '';
        if (order.lines.length === 0) {
            list.appendChild(el('div', 'cart-empty', 'No items yet — tap a menu item to add it.'));
            return;
        }
        order.lines.forEach(function (line) {
            var row = el('div', 'cart-item' + (line.id === selectedLineId ? ' selected' : ''));
            row.setAttribute('data-line-id', line.id);

            var main = el('div', 'item-main');
            var name = el('span');
            name.appendChild(el('b', null, line.qty + ' × ' + line.name));
            main.appendChild(name);
            main.appendChild(el('span', null, money(line.lineTotal)));
            row.appendChild(main);

            if (line.addons.length) {
                row.appendChild(el('div', 'item-line-details',
                    line.addons.map(function (a) { return '+ ' + a.name; }).join(', ')));
            }

            var controls = el('div', 'qty-controls');
            [['minus', '−', 'Remove one'], ['plus', '+', 'Add one'], ['remove', '✕', 'Remove line']]
                .forEach(function (spec) {
                    var b = el('button', 'qty-btn ' + spec[0], spec[1]);
                    b.type = 'button';
                    b.title = spec[2];
                    b.setAttribute('data-act', spec[0]);
                    controls.appendChild(b);
                });
            row.appendChild(controls);
            list.appendChild(row);
        });
    }

    function renderPayment() {
        document.querySelectorAll('.pay-btn').forEach(function (b) {
            b.classList.toggle('active', b.getAttribute('data-method') === order.paymentMethod);
        });
    }

    // ---- receipts: one set of builders feeds the live preview and both print targets (FR-1.5, FR-2) --------

    function customerCopy() {
        var root = el('div');
        var head = el('div', 'center');
        head.appendChild(el('div', 'receipt-name', RESTAURANT));
        head.appendChild(el('div', 'order-number', '#' + order.orderNumber));
        head.appendChild(el('div', 'receipt-date', stamp()));
        head.appendChild(el('div', 'copy-band',
            'CUSTOMER COPY — ' + (order.paymentMethod === 'MOMO' ? 'MoMo' : 'Cash')));
        root.appendChild(head);

        var tbody = el('tbody');
        order.lines.forEach(function (line) {
            var tr = el('tr');
            var left = el('td');
            left.appendChild(document.createTextNode(line.qty + ' × ' + line.name));
            line.addons.forEach(function (a) { left.appendChild(el('span', 'addon-text', '+ ' + a.name)); });
            var right = el('td', 'right', Number(line.lineTotal).toLocaleString('en-US'));
            tr.appendChild(left);
            tr.appendChild(right);
            tbody.appendChild(tr);
        });
        var table = el('table', 'items-table');
        table.appendChild(tbody);
        root.appendChild(table);

        var total = el('div', 'total-row');
        total.appendChild(el('span', null, 'TOTAL:'));
        total.appendChild(el('span', null, money(order.total)));
        root.appendChild(total);
        return root;
    }

    function kitchenTicket() {
        var root = el('div');
        var head = el('div', 'center');
        head.appendChild(el('div', 'kitchen-title', 'KITCHEN TICKET'));
        head.appendChild(el('div', 'order-number', '#' + order.orderNumber));
        root.appendChild(head);

        var tbody = el('tbody');
        order.lines.forEach(function (line) {
            var tr = el('tr');
            var td = el('td');
            td.appendChild(el('span', 'k-qty', line.qty + ' ×'));
            td.appendChild(document.createTextNode(' ' + line.name));
            line.addons.forEach(function (a) { td.appendChild(el('span', 'addon-text', '+ ' + a.name)); });
            tr.appendChild(td);
            tbody.appendChild(tr);
        });
        var table = el('table', 'items-table');
        table.appendChild(tbody);
        root.appendChild(table);
        return root;
    }

    function renderReceipts() {
        var preview = $('invoice-wrap');
        preview.textContent = '';
        preview.appendChild(customerCopy());
        preview.appendChild(el('div', 'tear-line'));
        preview.appendChild(kitchenTicket());

        var c = $('print-customer');
        c.textContent = '';
        c.appendChild(customerCopy());
        var k = $('print-kitchen');
        k.textContent = '';
        k.appendChild(kitchenTicket());
    }

    // ---- modal ------------------------------------------------------------------------------------------

    function showModal(title, message, buttons) {
        var root = $('modal-root');
        root.textContent = '';
        var overlay = el('div', 'modal-overlay');
        var box = el('div', 'modal-box');
        box.setAttribute('role', 'dialog');
        box.appendChild(el('h3', null, title));
        box.appendChild(el('p', null, message));
        var row = el('div', 'modal-actions');
        buttons.forEach(function (spec) {
            var b = el('button', 'modal-btn ' + (spec.kind || ''), spec.label);
            b.type = 'button';
            b.addEventListener('click', function () {
                root.textContent = '';
                spec.onClick();
            });
            row.appendChild(b);
        });
        box.appendChild(row);
        overlay.appendChild(box);
        root.appendChild(overlay);
        row.firstChild.focus();
    }

    // ---- the two-step print sequence (FR-2) ------------------------------------------------------------

    function printTarget(id) {
        return new Promise(function (resolve) {
            var target = $(id);
            target.classList.add('active');
            // window.print() blocks until the dialog closes: that wait IS the gap between the two copies (FR-2.2).
            setTimeout(function () {
                window.print();
                target.classList.remove('active');
                resolve();
            }, 80);
        });
    }

    async function finalizeCurrent(orderNumber) {
        var r = await call('POST', '/api/order/finalize/' + orderNumber);
        if (r.ok) {
            toast('Order #' + orderNumber + ' finalized.', 'success');
            // Reload so stock levels, low-stock states and the counter strip all come fresh from the database.
            setTimeout(function () { window.location.reload(); }, 500);
            return true;
        }
        if (r.data && r.data.order) { setOrder(r.data.order); }
        toast((r.data && r.data.message) || 'Could not finalize the order.', 'error');
        return false;
    }

    async function printFlow() {
        if (printing) { return; }
        if (!order || order.lines.length === 0) {
            toast('Add at least one item before printing.', 'error');
            return;
        }
        printing = true;
        render();
        try {
            if (!(await act('POST', '/api/order/print/start'))) { return; }
            await printTarget('print-customer');
            var afterCustomer = await act('POST', '/api/order/print/customer-done');
            if (!afterCustomer) { return; }
            await printTarget('print-kitchen');
            await finalizeCurrent(afterCustomer.orderNumber);
        } finally {
            printing = false;
            render();
        }
    }

    // FR-2.4: the app was closed mid-print. Print completion is unverifiable, so the cashier decides.
    function promptResume() {
        var n = order.orderNumber;
        var question = order.status === 'PRINTING_KITCHEN'
            ? 'Finish order #' + n + ' — was the kitchen ticket printed?'
            : 'Finish order #' + n + ' — were both copies printed?';
        showModal('Unfinished print', question, [
            { label: 'Yes — finish the order', kind: 'primary', onClick: function () { queue(function () { return finalizeCurrent(n); }); } },
            { label: 'No — print again', kind: 'secondary', onClick: function () { queue(function () { return act('POST', '/api/order/print/reopen'); }); } }
        ]);
    }

    // ---- events -----------------------------------------------------------------------------------------

    $('menu-container').addEventListener('click', function (ev) {
        var card = ev.target.closest('.menu-card');
        if (!card || printing) { return; }
        var id = Number(card.getAttribute('data-item-id'));
        queue(async function () {
            var o = await act('POST', '/api/order/items', { menuItemId: id });
            if (o) {
                var line = o.lines.find(function (l) { return l.menuItemId === id; });
                if (line) { selectedLineId = line.id; render(); }
            }
        });
    });

    $('cart-items-list').addEventListener('click', function (ev) {
        var row = ev.target.closest('.cart-item');
        if (!row || printing) { return; }
        var lineId = Number(row.getAttribute('data-line-id'));
        var button = ev.target.closest('.qty-btn');
        if (!button) {
            selectedLineId = lineId;
            render();
            return;
        }
        var action = button.getAttribute('data-act');
        queue(function () {
            if (action === 'remove') { return act('DELETE', '/api/order/lines/' + lineId); }
            return act('POST', '/api/order/lines/' + lineId + '/quantity', { delta: action === 'plus' ? 1 : -1 });
        });
    });

    $('addon-bar').addEventListener('click', function (ev) {
        var tag = ev.target.closest('.addon-tag');
        if (!tag || printing) { return; }
        if (selectedLineId === null) {
            toast('Add an item first, then pick its add-ons.', 'error');
            return;
        }
        var addonId = Number(tag.getAttribute('data-addon-id'));
        var lineId = selectedLineId;
        queue(function () { return act('POST', '/api/order/lines/' + lineId + '/addons', { addonId: addonId }); });
    });

    document.querySelectorAll('.pay-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            if (printing) { return; }
            var method = btn.getAttribute('data-method');
            queue(function () { return act('PUT', '/api/order/payment', { method: method }); });
        });
    });

    $('print-btn').addEventListener('click', function () { queue(printFlow); });

    // FR-1.9: cashier may discard the unfinalized order, after a confirmation.
    $('cancel-btn').addEventListener('click', function () {
        showModal('Cancel this order?', 'All items will be removed.', [
            { label: 'Keep order', kind: 'secondary', onClick: function () { } },
            { label: 'Cancel order', kind: 'danger', onClick: function () { queue(function () { return act('DELETE', '/api/order'); }); } }
        ]);
    });

    // ---- start-up ---------------------------------------------------------------------------------------

    function tick() {
        var clock = $('clock');
        if (clock) { clock.textContent = stamp(); }
    }
    tick();
    setInterval(tick, 1000);

    refresh().then(function (o) {
        if (o && (o.status === 'PRINTING_CUSTOMER' || o.status === 'PRINTING_KITCHEN')) { promptResume(); }
    });
})();
