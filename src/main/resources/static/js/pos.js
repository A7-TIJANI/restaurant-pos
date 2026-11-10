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
    refreshOwner();
    loadOrders();
})();
