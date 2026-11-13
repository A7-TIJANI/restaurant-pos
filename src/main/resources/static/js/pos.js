// POS screen: cart, add-ons, payment toggle, two-step print flow, crash-resume prompt, owner lock/PIN,
// today's orders and the owner's edit / void / reprint.
// All business and security rules live on the server (/api/**); this file renders what it is given.
(function () {
    'use strict';

    var RESTAURANT = document.body.getAttribute('data-restaurant') || '';
    var TZ = 'Africa/Kigali'; // A13: receipts show Kigali time whatever the laptop's own zone is

    var order = null;            // the in-progress order (cashier cart)
    var selectedLineId = null;
    var printing = false;
    var todayOrders = [];        // finalized/voided orders of today, from /api/orders/today
    var owner = { pinSet: true, unlocked: false, resetAt: null };

    function $(id) { return document.getElementById(id); }

    function el(tag, className, text) {
        var e = document.createElement(tag);
        if (className) { e.className = className; }
        if (text !== undefined && text !== null) { e.textContent = text; }
        return e;
    }

    // FR-1.8: three bundled sounds (no network, NFR-1). A blocked autoplay or missing audio device is never an error.
    var muted = document.body.getAttribute('data-muted') === 'true';
    var SOUNDS = {};
    ['click', 'blocked', 'success'].forEach(function (name) {
        try { SOUNDS[name] = new Audio('/sounds/' + name + '.wav'); } catch (ignore) { /* no audio support */ }
    });
    function sound(name) {
        if (muted || !SOUNDS[name]) { return; }
        try {
            SOUNDS[name].currentTime = 0;
            var p = SOUNDS[name].play();
            if (p && p.catch) { p.catch(function () { /* autoplay refused */ }); }
        } catch (ignore) { /* ignore */ }
    }

    function money(n) { return Number(n).toLocaleString('en-US') + ' RWF'; }

    function stamp(when) {
        return new Date(when || Date.now())
            .toLocaleString('en-GB', { timeZone: TZ, dateStyle: 'medium', timeStyle: 'medium' });
    }

    function timeOf(when) {
        return new Date(when).toLocaleTimeString('en-GB', { timeZone: TZ, hour: '2-digit', minute: '2-digit' });
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
            if (res.status === 401 && data && data.error === 'OWNER_REQUIRED') {
                // The 10-minute idle window ended (A15): the server says so, the UI follows.
                if (owner.unlocked) { toast('Owner session ended. Enter the PIN again.', 'error'); }
                owner.unlocked = false;
                renderOwner();
            }
            return { ok: res.ok, status: res.status, data: data };
        } catch (e) {
            return { ok: false, status: 0, data: { message: 'Cannot reach the POS service. Is the app still running?' } };
        }
    }

    // Runs a call that returns the order; on failure shows the server's message and re-syncs with the server.
    async function act(method, url, body) {
        var r = await call(method, url, body);
        if (!r.ok) {
            sound('blocked');
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
        renderOrders();
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

    // ---- receipts: one set of builders feeds the live preview, both print targets and reprints ----------
    // o is any order shaped like the API's OrderView; opts = { reprint: true, when: isoInstant }.

    function customerCopy(o, opts) {
        opts = opts || {};
        var root = el('div');
        var head = el('div', 'center');
        if (opts.reprint) { head.appendChild(el('div', 'reprint-mark', '** REPRINT **')); }
        head.appendChild(el('div', 'receipt-name', RESTAURANT));
        head.appendChild(el('div', 'order-number', '#' + o.orderNumber));
        head.appendChild(el('div', 'receipt-date', stamp(opts.when)));
        head.appendChild(el('div', 'copy-band',
            'CUSTOMER COPY — ' + (o.paymentMethod === 'MOMO' ? 'MoMo' : 'Cash')));
        root.appendChild(head);

        var tbody = el('tbody');
        o.lines.forEach(function (line) {
            var tr = el('tr');
            var left = el('td');
            left.appendChild(document.createTextNode(line.qty + ' × ' + line.name));
            line.addons.forEach(function (a) { left.appendChild(el('span', 'addon-text', '+ ' + a.name)); });
            tr.appendChild(left);
            tr.appendChild(el('td', 'right', Number(line.lineTotal).toLocaleString('en-US')));
            tbody.appendChild(tr);
        });
        var table = el('table', 'items-table');
        table.appendChild(tbody);
        root.appendChild(table);

        var total = el('div', 'total-row');
        total.appendChild(el('span', null, 'TOTAL:'));
        total.appendChild(el('span', null, money(o.total)));
        root.appendChild(total);
        return root;
    }

    function kitchenTicket(o, opts) {
        opts = opts || {};
        var root = el('div');
        var head = el('div', 'center');
        if (opts.reprint) { head.appendChild(el('div', 'reprint-mark', '** REPRINT **')); }
        head.appendChild(el('div', 'kitchen-title', 'KITCHEN TICKET'));
        head.appendChild(el('div', 'order-number', '#' + o.orderNumber));
        root.appendChild(head);

        var tbody = el('tbody');
        o.lines.forEach(function (line) {
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

    function fillPrintTargets(o, opts) {
        var c = $('print-customer');
        c.textContent = '';
        c.appendChild(customerCopy(o, opts));
        var k = $('print-kitchen');
        k.textContent = '';
        k.appendChild(kitchenTicket(o, opts));
    }

    function renderReceipts() {
        var preview = $('invoice-wrap');
        preview.textContent = '';
        preview.appendChild(customerCopy(order));
        preview.appendChild(el('div', 'tear-line'));
        preview.appendChild(kitchenTicket(order));
        fillPrintTargets(order);
    }

    // ---- modals -----------------------------------------------------------------------------------------

    function closeModal() { $('modal-root').textContent = ''; }

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

    // A modal with a form. nodes = form controls; onSubmit() resolves to an error message, or null on success
    // (which closes the modal). The error shows inside the modal so the cashier/owner can fix it and retry.
    function showForm(title, intro, nodes, submitLabel, onSubmit, submitKind) {
        var root = $('modal-root');
        root.textContent = '';
        var overlay = el('div', 'modal-overlay');
        var box = el('form', 'modal-box');
        box.setAttribute('role', 'dialog');
        box.noValidate = true;
        box.appendChild(el('h3', null, title));
        if (intro) { box.appendChild(el('p', 'modal-intro', intro)); }
        nodes.forEach(function (n) { box.appendChild(n); });
        var error = el('p', 'form-error');
        error.hidden = true;
        box.appendChild(error);
        var row = el('div', 'modal-actions');
        var cancel = el('button', 'modal-btn secondary', 'Cancel');
        cancel.type = 'button';
        cancel.addEventListener('click', closeModal);
        var submit = el('button', 'modal-btn ' + (submitKind || 'primary'), submitLabel);
        submit.type = 'submit';
        row.appendChild(cancel);
        row.appendChild(submit);
        box.appendChild(row);
        box.addEventListener('submit', async function (ev) {
            ev.preventDefault();
            submit.disabled = true;
            error.hidden = true;
            var message = await onSubmit();
            if (message) {
                error.textContent = message;
                error.hidden = false;
                submit.disabled = false;
            } else {
                closeModal();
            }
        });
        overlay.appendChild(box);
        root.appendChild(overlay);
        var first = box.querySelector('input, select');
        if (first) { first.focus(); }
        return box;
    }

    function field(label, type, attrs) {
        var wrap = el('label', 'form-field');
        wrap.appendChild(el('span', null, label));
        var input = el('input');
        input.type = type;
        Object.keys(attrs || {}).forEach(function (k) { input.setAttribute(k, attrs[k]); });
        wrap.appendChild(input);
        return { wrap: wrap, input: input };
    }

    // ---- owner: lock, PIN, session (A14, A15) --------------------------------------------------------------

    async function refreshOwner() {
        var r = await call('GET', '/api/auth/status');
        if (r.ok) {
            owner = r.data;
            renderOwner();
        }
    }

    function renderOwner() {
        var btn = $('owner-btn');
        btn.textContent = owner.unlocked ? 'Lock owner area' : 'Owner';
        btn.classList.toggle('unlocked', !!owner.unlocked);
        document.body.classList.toggle('owner', !!owner.unlocked);
        $('reports-btn').hidden = !owner.unlocked;
        $('data-btn').hidden = !owner.unlocked;
        $('settings-btn').hidden = !owner.unlocked;
        var banner = $('reset-banner');
        var showBanner = !owner.pinSet && !!owner.resetAt;
        banner.hidden = !showBanner;
        if (showBanner) {
            $('reset-text').textContent = 'PIN was reset on ' + stamp(owner.resetAt) + ' — set a new PIN now.';
        }
        renderOrders();
    }

    function showLogin() {
        var pin = field('Owner PIN', 'password', { inputmode: 'numeric', maxlength: '6', autocomplete: 'off' });
        showForm('Owner PIN', null, [pin.wrap], 'Unlock', async function () {
            var r = await call('POST', '/api/auth/login', { pin: pin.input.value });
            if (r.ok) {
                await refreshOwner();
                return null;
            }
            pin.input.value = '';
            pin.input.focus();
            return (r.data && r.data.message) || 'Could not unlock.';
        });
    }

    function showSetupPin() {
        var pin = field('New PIN (4–6 digits)', 'password', { inputmode: 'numeric', maxlength: '6', autocomplete: 'off' });
        var again = field('Repeat the PIN', 'password', { inputmode: 'numeric', maxlength: '6', autocomplete: 'off' });
        showForm('Set the owner PIN', 'The PIN protects reports, order changes and settings. Keep it private.',
            [pin.wrap, again.wrap], 'Save PIN', async function () {
                if (!/^\d{4,6}$/.test(pin.input.value)) { return 'The PIN must be 4 to 6 digits.'; }
                if (pin.input.value !== again.input.value) { return 'The two PINs do not match.'; }
                var r = await call('POST', '/api/auth/setup', { pin: pin.input.value });
                if (r.ok) {
                    await refreshOwner();
                    toast('PIN saved. Owner area unlocked.', 'success');
                    return null;
                }
                return (r.data && r.data.message) || 'Could not save the PIN.';
            });
    }

    $('owner-btn').addEventListener('click', function () {
        if (owner.unlocked) {
            queue(async function () {
                await call('POST', '/api/auth/lock');
                await refreshOwner();
                toast('Owner area locked.', 'info');
            });
        } else if (!owner.pinSet) {
            showSetupPin();
        } else {
            showLogin();
        }
    });
    $('reset-setup-btn').addEventListener('click', showSetupPin);

    // ---- today's orders (FR-3.1) + owner actions (FR-3.2, FR-3.4) ------------------------------------------

    async function loadOrders() {
        var r = await call('GET', '/api/orders/today');
        if (r.ok) {
            // The in-progress order is drawn from the live cart below, so it is never stale.
            todayOrders = r.data.filter(function (o) { return o.status === 'FINALIZED' || o.status === 'VOIDED'; });
            renderOrders();
        }
    }

    function payLabel(method) { return method === 'MOMO' ? 'MoMo' : 'Cash'; }

    function renderOrders() {
        var list = $('orders-list');
        list.textContent = '';
        var rows = [];
        if (order && order.lines.length > 0) {
            rows.push({ orderNumber: order.orderNumber, at: null, total: order.total,
                paymentMethod: order.paymentMethod, status: 'IN_PROGRESS' });
        }
        rows = rows.concat(todayOrders);
        if (rows.length === 0) {
            list.appendChild(el('div', 'cart-empty', 'No orders yet today.'));
            return;
        }
        rows.forEach(function (o) {
            var row = el('div', 'order-row' + (o.status === 'VOIDED' ? ' voided' : ''));
            row.appendChild(el('span', 'o-no', '#' + o.orderNumber));
            row.appendChild(el('span', 'o-time', o.at ? timeOf(o.at) : 'now'));
            row.appendChild(el('span', 'o-total', money(o.total)));
            row.appendChild(el('span', 'o-pay', payLabel(o.paymentMethod)));
            if (o.status === 'VOIDED') { row.appendChild(el('span', 'badge voided', 'Voided')); }
            if (o.status === 'IN_PROGRESS') { row.appendChild(el('span', 'badge progress', 'In progress')); }
            if (owner.unlocked && o.status === 'FINALIZED') {
                var actions = el('span', 'o-actions');
                [['edit', 'Edit'], ['reprint', 'Reprint'], ['void', 'Void']].forEach(function (spec) {
                    var b = el('button', 'o-btn ' + spec[0], spec[1]);
                    b.type = 'button';
                    b.setAttribute('data-act', spec[0]);
                    b.setAttribute('data-n', o.orderNumber);
                    actions.appendChild(b);
                });
                row.appendChild(actions);
            }
            list.appendChild(row);
        });
    }

    $('orders-list').addEventListener('click', function (ev) {
        var b = ev.target.closest('.o-btn');
        if (!b || printing) { return; }
        var n = Number(b.getAttribute('data-n'));
        var action = b.getAttribute('data-act');
        if (action === 'edit') { openEdit(n); }
        if (action === 'void') { openVoid(n); }
        if (action === 'reprint') { queue(function () { return reprint(n); }); }
    });

    function menuChoices() {
        return Array.prototype.map.call(document.querySelectorAll('#menu-container .menu-card'), function (card) {
            return { id: card.getAttribute('data-item-id'), name: card.querySelector('b').textContent };
        });
    }

    async function openEdit(n) {
        var r = await call('GET', '/api/owner/orders/' + n);
        if (!r.ok) { toast((r.data && r.data.message) || 'Could not open the order.', 'error'); return; }
        var o = r.data.order;

        var payWrap = el('label', 'form-field');
        payWrap.appendChild(el('span', null, 'Payment method'));
        var pay = el('select');
        [['CASH', 'Cash'], ['MOMO', 'MoMo']].forEach(function (p) {
            var opt = el('option', null, p[1]);
            opt.value = p[0];
            if (p[0] === o.paymentMethod) { opt.selected = true; }
            pay.appendChild(opt);
        });
        payWrap.appendChild(pay);

        var linesBox = el('div', 'edit-lines');
        linesBox.appendChild(el('div', 'edit-lines-title', 'Quantities (0 removes a line)'));
        var qtyInputs = [];
        o.lines.forEach(function (line) {
            var row = el('label', 'edit-line');
            row.appendChild(el('span', null, line.name));
            var input = el('input');
            input.type = 'number';
            input.min = '0';
            input.value = line.qty;
            input.setAttribute('data-line-id', line.id);
            input.setAttribute('data-orig', line.qty);
            row.appendChild(input);
            linesBox.appendChild(row);
            qtyInputs.push(input);
        });

        var addWrap = el('div', 'edit-add');
        addWrap.appendChild(el('span', null, 'Add item'));
        var addSelect = el('select');
        var none = el('option', null, '— none —');
        none.value = '';
        addSelect.appendChild(none);
        menuChoices().forEach(function (c) {
            var opt = el('option', null, c.name);
            opt.value = c.id;
            addSelect.appendChild(opt);
        });
        var addQty = el('input');
        addQty.type = 'number';
        addQty.min = '1';
        addQty.value = '1';
        addWrap.appendChild(addSelect);
        addWrap.appendChild(addQty);

        var reason = field('Reason (required, saved in the audit log)', 'text', { maxlength: '255' });

        showForm('Edit order #' + n, 'Stock is adjusted for every change.',
            [payWrap, linesBox, addWrap, reason.wrap], 'Save changes', async function () {
                var quantities = {};
                qtyInputs.forEach(function (input) {
                    if (input.value !== input.getAttribute('data-orig')) {
                        quantities[input.getAttribute('data-line-id')] = Number(input.value);
                    }
                });
                var additions = addSelect.value
                    ? [{ menuItemId: Number(addSelect.value), qty: Number(addQty.value) }] : [];
                var res = await call('PUT', '/api/owner/orders/' + n, {
                    paymentMethod: pay.value, quantities: quantities, additions: additions, reason: reason.input.value
                });
                if (res.ok) {
                    toast('Order #' + n + ' updated.', 'success');
                    // Reload: stock levels, the counter strip and the list all change.
                    setTimeout(function () { window.location.reload(); }, 400);
                    return null;
                }
                return (res.data && res.data.message) || 'Could not save the changes.';
            });
    }

    function openVoid(n) {
        var reason = field('Reason (required, saved in the audit log)', 'text', { maxlength: '255' });
        showForm('Void order #' + n + '?', 'The stock is put back and the order stays in the books marked as voided.',
            [reason.wrap], 'Void order', async function () {
                var res = await call('POST', '/api/owner/orders/' + n + '/void', { reason: reason.input.value });
                if (res.ok) {
                    toast('Order #' + n + ' voided.', 'success');
                    setTimeout(function () { window.location.reload(); }, 400);
                    return null;
                }
                return (res.data && res.data.message) || 'Could not void the order.';
            }, 'danger');
    }

    async function reprint(n) {
        var r = await call('POST', '/api/owner/orders/' + n + '/reprint');
        if (!r.ok) { toast((r.data && r.data.message) || 'Could not reprint.', 'error'); return; }
        printing = true;
        try {
            fillPrintTargets(r.data.order, { reprint: true, when: r.data.finalizedAt });
            await printTarget('print-customer');
            await printTarget('print-kitchen');
            toast('Order #' + n + ' reprinted.', 'success');
        } finally {
            printing = false;
            render();
        }
    }


    // ---- reports (FR-4): owner-only, four tabs, printable on 72mm ------------------------------------------

    var REPORT_TABS = [
        ['today', 'Today', '/api/owner/reports/today'],
        ['days', '7 Days', '/api/owner/reports/days'],
        ['weeks', '4 Weeks', '/api/owner/reports/weeks'],
        ['months', '12 Months', '/api/owner/reports/months']
    ];

    function longDate(isoDate) {
        // isoDate is a Kigali calendar day (yyyy-mm-dd); format it without any timezone shifting
        return new Date(isoDate + 'T12:00:00Z')
            .toLocaleDateString('en-GB', { timeZone: 'UTC', weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
    }

    function cell(tag, text, className) { return el(tag, className || null, text); }

    function reportScreenNode(kind, data) {
        var root = el('div', 'report');
        if (kind === 'today') {
            root.appendChild(el('h4', null, longDate(data.date)));
            var sum = el('table', 'report-table summary');
            [['Orders', data.totals.orders], ['Revenue', money(data.totals.revenue)],
                ['Cash', money(data.totals.cash)], ['MoMo', money(data.totals.momo)]].forEach(function (r) {
                var tr = el('tr');
                tr.appendChild(cell('th', r[0]));
                tr.appendChild(cell('td', r[1], 'num'));
                sum.appendChild(tr);
            });
            root.appendChild(sum);
            if (data.voidedOrders > 0) {
                root.appendChild(el('p', 'report-note', data.voidedOrders + ' voided order' + (data.voidedOrders === 1 ? '' : 's')
                    + ' (' + money(data.voidedTotal) + ') not counted above.'));
            }
            root.appendChild(el('h4', null, 'Items sold'));
            if (data.items.length === 0) {
                root.appendChild(el('p', 'report-note', 'Nothing sold yet today.'));
            } else {
                var items = el('table', 'report-table');
                var head = el('tr');
                ['Item', 'Qty', 'Revenue'].forEach(function (h, i) { head.appendChild(cell('th', h, i ? 'num' : '')); });
                items.appendChild(head);
                data.items.forEach(function (it, i) {
                    var tr = el('tr', i < 5 ? 'top-item' : '');
                    tr.appendChild(cell('td', (i < 5 ? (i + 1) + '. ' : '') + it.name));
                    tr.appendChild(cell('td', it.qty, 'num'));
                    tr.appendChild(cell('td', money(it.revenue), 'num'));
                    items.appendChild(tr);
                });
                root.appendChild(items);
            }
            return root;
        }
        root.appendChild(el('h4', null, data.title));
        var table = el('table', 'report-table');
        var h = el('tr');
        ['Period', 'Orders', 'Revenue', 'Cash', 'MoMo'].forEach(function (t, i) { h.appendChild(cell('th', t, i ? 'num' : '')); });
        table.appendChild(h);
        data.rows.forEach(function (r) {
            var tr = el('tr', r.orders === 0 ? 'empty-row' : '');
            tr.appendChild(cell('td', r.label));
            tr.appendChild(cell('td', r.orders, 'num'));
            tr.appendChild(cell('td', money(r.revenue), 'num'));
            tr.appendChild(cell('td', money(r.cash), 'num'));
            tr.appendChild(cell('td', money(r.momo), 'num'));
            table.appendChild(tr);
        });
        var t = data.totals;
        var total = el('tr', 'total-row-r');
        total.appendChild(cell('td', 'Total'));
        total.appendChild(cell('td', t.orders, 'num'));
        total.appendChild(cell('td', money(t.revenue), 'num'));
        total.appendChild(cell('td', money(t.cash), 'num'));
        total.appendChild(cell('td', money(t.momo), 'num'));
        table.appendChild(total);
        root.appendChild(table);
        return root;
    }

    // 72mm paper fits ~33 characters, so each row is stacked instead of tabulated.
    function line(left, right) {
        var d = el('div', 'rp-line');
        d.appendChild(el('span', null, left));
        d.appendChild(el('span', null, right));
        return d;
    }

    function reportPrintNode(kind, data) {
        var root = el('div');
        var head = el('div', 'center');
        head.appendChild(el('div', 'receipt-name', RESTAURANT));
        head.appendChild(el('div', 'copy-band', 'REPORT — ' + (kind === 'today' ? 'TODAY' : data.title.toUpperCase())));
        head.appendChild(el('div', 'receipt-date', 'Printed ' + stamp()));
        root.appendChild(head);

        if (kind === 'today') {
            root.appendChild(el('div', 'rp-label', longDate(data.date)));
            root.appendChild(line('Orders', String(data.totals.orders)));
            root.appendChild(line('Revenue', money(data.totals.revenue)));
            root.appendChild(line('Cash', money(data.totals.cash)));
            root.appendChild(line('MoMo', money(data.totals.momo)));
            if (data.voidedOrders > 0) {
                root.appendChild(el('div', 'rp-note', 'Not counted: ' + data.voidedOrders + ' voided (' + money(data.voidedTotal) + ')'));
            }
            root.appendChild(el('div', 'rp-section', 'ITEMS SOLD'));
            data.items.forEach(function (it, i) {
                var block = el('div', 'rp-row');
                block.appendChild(el('div', 'rp-label', (i < 5 ? (i + 1) + '. ' : '') + it.name));
                block.appendChild(line(it.qty + ' sold', money(it.revenue)));
                root.appendChild(block);
            });
            return root;
        }
        data.rows.forEach(function (r) {
            var block = el('div', 'rp-row');
            block.appendChild(el('div', 'rp-label', r.label));
            block.appendChild(line('Orders ' + r.orders, money(r.revenue)));
            block.appendChild(line('Cash ' + Number(r.cash).toLocaleString('en-US'), 'MoMo ' + Number(r.momo).toLocaleString('en-US')));
            root.appendChild(block);
        });
        var t = data.totals;
        var total = el('div', 'rp-total');
        total.appendChild(el('div', 'rp-label', 'TOTAL'));
        total.appendChild(line('Orders ' + t.orders, money(t.revenue)));
        total.appendChild(line('Cash ' + Number(t.cash).toLocaleString('en-US'), 'MoMo ' + Number(t.momo).toLocaleString('en-US')));
        root.appendChild(total);
        return root;
    }

    function openReports() {
        var root = $('modal-root');
        root.textContent = '';
        var overlay = el('div', 'modal-overlay');
        var box = el('div', 'modal-box wide');
        box.setAttribute('role', 'dialog');
        box.appendChild(el('h3', null, 'Reports'));

        var tabs = el('div', 'report-tabs');
        var body = el('div', 'report-body');
        var actions = el('div', 'modal-actions');
        var printBtn = el('button', 'modal-btn primary', 'Print');
        printBtn.type = 'button';
        printBtn.disabled = true;
        var closeBtn = el('button', 'modal-btn secondary', 'Close');
        closeBtn.type = 'button';
        closeBtn.addEventListener('click', closeModal);
        actions.appendChild(printBtn);
        actions.appendChild(closeBtn);

        var loaded = null;   // { kind, data } of the tab on screen
        var tabButtons = REPORT_TABS.map(function (tab) {
            var b = el('button', 'report-tab', tab[1]);
            b.type = 'button';
            b.addEventListener('click', function () { show(tab, b); });
            tabs.appendChild(b);
            return b;
        });

        async function show(tab, button) {
            tabButtons.forEach(function (b) { b.classList.toggle('active', b === button); });
            printBtn.disabled = true;
            loaded = null;
            body.textContent = 'Loading…';
            var r = await call('GET', tab[2]);
            if (!owner.unlocked) { closeModal(); return; }   // the owner session ended (call() already said so)
            if (!r.ok) {
                body.textContent = (r.data && r.data.message) || 'Could not load the report.';
                return;
            }
            loaded = { kind: tab[0], data: r.data };
            body.textContent = '';
            body.appendChild(reportScreenNode(tab[0], r.data));
            printBtn.disabled = false;
        }

        printBtn.addEventListener('click', function () {
            if (!loaded) { return; }
            queue(async function () {
                var target = $('print-report');
                target.textContent = '';
                target.appendChild(reportPrintNode(loaded.kind, loaded.data));
                await printTarget('print-report');
            });
        });

        box.appendChild(tabs);
        box.appendChild(body);
        box.appendChild(actions);
        overlay.appendChild(box);
        root.appendChild(overlay);
        show(REPORT_TABS[0], tabButtons[0]);
    }

    $('reports-btn').addEventListener('click', openReports);

    // ---- settings (FR-8) and first-run wizard ---------------------------------------------------------------

    async function openSettings() {
        var r = await call('GET', '/api/owner/settings');
        if (!r.ok) { return; }
        var cur = r.data;
        var name = field('Restaurant name (printed on receipts)', 'text', { maxlength: '100' });
        name.input.value = cur.restaurantName;
        var low = field('Low-stock warning level (default for new items)', 'number', { min: '0', max: '1000' });
        low.input.value = cur.lowStockDefault;
        var folder = field('Backup folder (on this laptop)', 'text');
        folder.input.value = cur.backupPath;
        var muteWrap = el('label', 'form-field check-field');
        var mute = el('input');
        mute.type = 'checkbox';
        mute.checked = cur.soundMuted;
        muteWrap.appendChild(mute);
        muteWrap.appendChild(el('span', null, 'Mute sound effects'));
        var pinBtn = el('button', 'modal-btn secondary', 'Change PIN…');
        pinBtn.type = 'button';
        pinBtn.addEventListener('click', showChangePin);
        showForm('Settings', null, [name.wrap, low.wrap, folder.wrap, muteWrap, pinBtn], 'Save', async function () {
            var res = await call('PUT', '/api/owner/settings', {
                restaurantName: name.input.value, lowStockDefault: Number(low.input.value),
                soundMuted: mute.checked, backupPath: folder.input.value
            });
            if (!res.ok) { return (res.data && res.data.message) || 'Could not save the settings.'; }
            muted = res.data.soundMuted;
            document.body.setAttribute('data-muted', String(muted));
            if (res.data.restaurantName !== RESTAURANT) {
                toast('Saved. Reloading to use the new name…', 'success');
                setTimeout(function () { window.location.reload(); }, 800);
            } else {
                toast('Settings saved.', 'success');
            }
            return null;
        });
    }

    function showChangePin() {
        var cur = field('Current PIN', 'password', { inputmode: 'numeric', maxlength: '6', autocomplete: 'off' });
        var next = field('New PIN (4–6 digits)', 'password', { inputmode: 'numeric', maxlength: '6', autocomplete: 'off' });
        var again = field('Repeat the new PIN', 'password', { inputmode: 'numeric', maxlength: '6', autocomplete: 'off' });
        showForm('Change PIN', null, [cur.wrap, next.wrap, again.wrap], 'Change PIN', async function () {
            if (!/^\d{4,6}$/.test(next.input.value)) { return 'The new PIN must be 4 to 6 digits.'; }
            if (next.input.value !== again.input.value) { return 'The two new PINs do not match.'; }
            var res = await call('POST', '/api/owner/pin', { currentPin: cur.input.value, newPin: next.input.value });
            if (!res.ok) { return (res.data && res.data.message) || 'Could not change the PIN.'; }
            toast('PIN changed.', 'success');
            return null;
        });
    }

    $('settings-btn').addEventListener('click', openSettings);

    // First run: no PIN and no reset in progress. Name + PIN are forced, so there is no Cancel.
    function showFirstRun() {
        var name = field('Restaurant name', 'text', { maxlength: '100', autocomplete: 'off' });
        var pin = field('Owner PIN (4–6 digits)', 'password', { inputmode: 'numeric', maxlength: '6', autocomplete: 'off' });
        var again = field('Repeat the PIN', 'password', { inputmode: 'numeric', maxlength: '6', autocomplete: 'off' });
        var box = showForm('Welcome to Epic IT POS', 'Set up your restaurant. The name is printed on every receipt; the PIN protects reports, order changes and settings.',
            [name.wrap, pin.wrap, again.wrap], 'Start', async function () {
                if (!name.input.value.trim()) { return 'Please enter the restaurant name.'; }
                if (!/^\d{4,6}$/.test(pin.input.value)) { return 'The PIN must be 4 to 6 digits.'; }
                if (pin.input.value !== again.input.value) { return 'The two PINs do not match.'; }
                var r = await call('POST', '/api/auth/setup', { pin: pin.input.value, restaurantName: name.input.value });
                if (!r.ok) { return (r.data && r.data.message) || 'Could not finish the setup.'; }
                window.location.reload();
                return null;
            });
        var cancel = box.querySelector('.modal-btn.secondary');
        if (cancel) { cancel.hidden = true; }
        name.input.focus();
    }

    // ---- data: Excel export and backups (FR-5, FR-7) ----------------------------------------------------

    function kb(bytes) { return Math.max(1, Math.round(bytes / 1024)).toLocaleString('en-US') + ' KB'; }

    function confirmRestore(b) {
        showModal('Restore this backup?',
            'Everything sold after ' + stamp(b.modified) + ' will be missing from the restored data. Your current data is kept ' +
            'in the data folder as a "before-restore" file, but the POS must be closed and opened again to finish.',
            [{ label: 'Cancel', kind: 'secondary', onClick: openData },
             { label: 'Restore', kind: 'danger', onClick: async function () {
                var r = await call('POST', '/api/owner/restore', { name: b.name });
                if (!owner.unlocked) { return; }
                if (!r.ok) {
                    showModal('Cannot restore', (r.data && r.data.message) || 'The restore failed.', [{ label: 'OK', onClick: openData }]);
                    return;
                }
                showModal('Restore ready',
                    'Backup of "' + r.data.restaurantName + '" with ' + r.data.orders + ' finished orders is ready. ' +
                    'Close the POS window and open it again to finish. Nothing has changed yet.',
                    [{ label: 'OK', onClick: openData }]);
            } }]);
    }

    function openData() {
        var root = $('modal-root');
        root.textContent = '';
        var overlay = el('div', 'modal-overlay');
        var box = el('div', 'modal-box wide');
        box.setAttribute('role', 'dialog');
        box.appendChild(el('h3', null, 'Data'));

        var status = el('p', 'data-status');
        var excelBtn = el('button', 'modal-btn primary', 'Export to Excel');
        excelBtn.type = 'button';
        var backupBtn = el('button', 'modal-btn secondary', 'Back up now');
        backupBtn.type = 'button';
        var folder = el('p', 'data-folder');
        var pending = el('p', 'restore-pending');
        pending.hidden = true;
        var list = el('ul', 'backup-list');
        var closeBtn = el('button', 'modal-btn secondary', 'Close');
        closeBtn.type = 'button';
        closeBtn.addEventListener('click', closeModal);

        var buttons = el('div', 'modal-actions');
        buttons.appendChild(excelBtn);
        buttons.appendChild(backupBtn);

        function say(message, bad) {
            status.textContent = message;
            status.classList.toggle('form-error', !!bad);
        }

        async function loadList() {
            var r = await call('GET', '/api/owner/backups');
            if (!owner.unlocked) { closeModal(); return; }
            list.textContent = '';
            if (!r.ok) { say((r.data && r.data.message) || 'Could not read the backup folder.', true); return; }
            folder.textContent = 'Backup folder: ' + r.data.folder;
            pending.textContent = '';
            if (r.data.restorePending) {
                pending.appendChild(el('span', null, 'A restore is waiting. Close the POS and open it again to apply it. '));
                var cb = el('button', 'restore-btn', 'Cancel restore');
                cb.type = 'button';
                cb.addEventListener('click', async function () {
                    var c = await call('POST', '/api/owner/restore/cancel');
                    if (owner.unlocked) { say(c.ok ? 'Restore cancelled.' : 'Could not cancel the restore.', !c.ok); loadList(); }
                });
                pending.appendChild(cb);
            }
            pending.hidden = !r.data.restorePending;
            if (!r.data.backups.length) { list.appendChild(el('li', 'muted', 'No backups yet.')); return; }
            r.data.backups.forEach(function (b) {
                var li = el('li', 'backup-row');
                li.appendChild(el('span', null, b.name + '  ·  ' + kb(b.sizeBytes) + '  ·  ' + stamp(b.modified)));
                var rb = el('button', 'restore-btn', 'Restore');
                rb.type = 'button';
                rb.addEventListener('click', function () { confirmRestore(b); });
                li.appendChild(rb);
                list.appendChild(li);
            });
        }

        excelBtn.addEventListener('click', async function () {
            excelBtn.disabled = true;
            say('Building the workbook…');
            try {
                var res = await fetch('/api/owner/export.xlsx');
                if (!res.ok) {
                    var err = null;
                    try { err = await res.json(); } catch (ignore) { /* not JSON */ }
                    if (res.status === 401) { owner.unlocked = false; renderOwner(); closeModal(); toast('Owner session ended. Enter the PIN again.', 'error'); return; }
                    say((err && err.message) || 'The export failed.', true);
                    return;
                }
                var name = ((res.headers.get('Content-Disposition') || '').match(/filename="?([^";]+)"?/) || [])[1] || 'EpicIT-Report.xlsx';
                var url = URL.createObjectURL(await res.blob());
                var a = el('a');
                a.href = url;
                a.download = name;
                document.body.appendChild(a);
                a.click();
                a.remove();
                setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
                say('Saved ' + name + ' to your Downloads folder.');
                loadList();
            } catch (e) {
                say('Cannot reach the POS service. Is the app still running?', true);
            } finally {
                excelBtn.disabled = false;
            }
        });

        backupBtn.addEventListener('click', async function () {
            backupBtn.disabled = true;
            say('Backing up…');
            var r = await call('POST', '/api/owner/backup');
            backupBtn.disabled = false;
            if (!owner.unlocked) { closeModal(); return; }
            if (!r.ok) { say((r.data && r.data.message) || 'The backup failed.', true); return; }
            say('Backup saved: ' + r.data.file);
            loadList();
        });

        box.appendChild(status);
        box.appendChild(pending);
        box.appendChild(buttons);
        box.appendChild(folder);
        box.appendChild(el('h4', null, 'Backups (newest first, the last 30 are kept)'));
        box.appendChild(list);
        var actions = el('div', 'modal-actions');
        actions.appendChild(closeBtn);
        box.appendChild(actions);
        overlay.appendChild(box);
        root.appendChild(overlay);
        loadList();
    }

    $('data-btn').addEventListener('click', openData);

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
            sound('success');
            toast('Order #' + orderNumber + ' finalized.', 'success');
            // Reload so stock levels, low-stock states and the counter strip all come fresh from the database.
            setTimeout(function () { window.location.reload(); }, 1200);   // long enough for the chime to be heard
            return true;
        }
        if (r.data && r.data.order) { setOrder(r.data.order); }
        toast((r.data && r.data.message) || 'Could not finalize the order.', 'error');
        return false;
    }

    async function printFlow() {
        if (printing) { return; }
        if (!order || order.lines.length === 0) {
            sound('blocked');
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
                sound('click');
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
    refreshOwner().then(function () {
        if (!owner.pinSet && !owner.resetAt) { showFirstRun(); }
    });
    loadOrders();
})();
