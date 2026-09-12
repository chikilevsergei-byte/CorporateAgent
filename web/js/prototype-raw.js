function openModal(id) { document.getElementById(id).classList.add('active'); }
function closeModal(id) { document.getElementById(id).classList.remove('active'); }
document.querySelectorAll('.modal-overlay').forEach(overlay => {
    overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.classList.remove('active'); });
});
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') document.querySelectorAll('.modal-overlay.active').forEach(m => m.classList.remove('active')); });

function toggleSection(sectionId) {
    document.getElementById(sectionId).classList.toggle('collapsed');
}
function selectChat(el) { document.querySelectorAll('.chat-item').forEach(i => i.classList.remove('active')); el.classList.add('active'); }
function selectProject(el) { document.querySelectorAll('.project-item').forEach(i => i.classList.remove('active')); el.classList.add('active'); }
function selectSkill(el) { const p = el.parentElement; p.querySelectorAll('.skill-item').forEach(i => i.classList.remove('active')); el.classList.add('active'); }

function switchSettingsTab(event, tabId) {
    document.querySelectorAll('.settings-tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
    event.target.classList.add('active');
    document.getElementById(tabId).classList.add('active');
}

function selectRadio(el) {
    const group = el.parentElement;
    group.querySelectorAll('.radio-option').forEach(o => o.classList.remove('selected'));
    el.classList.add('selected');
    el.querySelector('input').checked = true;
    const dbSource = el.querySelector('input').value;
    const isNew = el.querySelector('.radio-text').textContent.includes('Новая');
    document.getElementById('newDbFields').style.display = isNew ? 'block' : 'none';
    document.getElementById('existingDbFields').style.display = isNew ? 'none' : 'block';
}

function selectDbItem(el) {
    el.parentElement.querySelectorAll('.db-select-item').forEach(i => i.classList.remove('selected'));
    el.classList.add('selected');
}

function nextWizardStep(step) {
    document.querySelectorAll('.wizard-step').forEach(s => s.classList.remove('active'));
    document.getElementById('wizardStep' + step).classList.add('active');
    document.querySelectorAll('.wizard-step-indicator').forEach(ind => {
        const indStep = parseInt(ind.dataset.step);
        ind.classList.remove('active', 'completed');
        if (indStep === step) ind.classList.add('active');
        else if (indStep < step) ind.classList.add('completed');
    });
}

function updateSliderValue(slider, valueId) {
    const value = parseFloat(slider.value).toFixed(1);
    document.getElementById(valueId).textContent = value;
    const percent = (slider.value / slider.max) * 100;
    slider.style.background = `linear-gradient(to right, var(--color-primary) 0%, var(--color-primary) ${percent}%, rgba(0,0,0,0.1) ${percent}%)`;
}
document.querySelectorAll('.slider').forEach(slider => {
    const percent = (slider.value / slider.max) * 100;
    slider.style.background = `linear-gradient(to right, var(--color-primary) 0%, var(--color-primary) ${percent}%, rgba(0,0,0,0.1) ${percent}%)`;
});

function toggleApiKey() {
    const input = document.getElementById('apiKey');
    input.type = input.type === 'password' ? 'text' : 'password';
}

function toggleDropdown(dropdownId, buttonEl, e) {
    if (e) e.stopPropagation();
    document.querySelectorAll('.dropdown-menu.active').forEach(menu => { if (menu.id !== dropdownId) menu.classList.remove('active'); });
    const dropdown = document.getElementById(dropdownId);
    if (dropdown) dropdown.classList.toggle('active');
}
function toggleDropdownItem(itemEl, type) { itemEl.classList.toggle('selected'); updateBadge(type); }
function updateBadge(type) {
    const badgeMap = { 'db': 'dbBadge', 'skills': 'skillsBadge', 'tools': 'toolsBadge' };
    const dropdownMap = { 'db': 'dbDropdown', 'skills': 'skillsDropdown', 'tools': 'toolsDropdown' };
    const dropdown = document.getElementById(dropdownMap[type]);
    const selectedCount = dropdown.querySelectorAll('.dropdown-item.selected').length;
    const badge = document.getElementById(badgeMap[type]);
    if (selectedCount > 0) { badge.textContent = selectedCount; badge.style.display = 'inline-flex'; }
    else { badge.style.display = 'none'; }
}
function resetDropdown(dropdownId, type) {
    const dropdown = document.getElementById(dropdownId);
    dropdown.querySelectorAll('.dropdown-item.selected').forEach(item => item.classList.remove('selected'));
    updateBadge(type);
    showToast('success', 'Сброшено', 'Выбор очищен');
}
function closeDropdown(dropdownId, type) {
    document.getElementById(dropdownId).classList.remove('active');
    const selectedCount = document.getElementById(dropdownId).querySelectorAll('.dropdown-item.selected').length;
    const names = { 'db': 'БД', 'skills': 'навыков', 'tools': 'инструментов' };
    if (selectedCount > 0) showToast('success', 'Применено', `Выбрано ${selectedCount} ${names[type]}`);
    else showToast('warning', 'Внимание', `Не выбрано ни одного ${names[type]}`);
}
document.addEventListener('click', (e) => {
    if (!e.target.closest('.dropdown-wrapper')) {
        document.querySelectorAll('.dropdown-menu.active').forEach(menu => menu.classList.remove('active'));
    }
});
['db', 'skills', 'tools'].forEach(type => updateBadge(type));

function showNotes() {
    document.getElementById('chatContent').style.display = 'none';
    document.getElementById('notesContent').style.display = 'flex';
    document.getElementById('inputArea').style.display = 'none';
}
function showChat() {
    document.getElementById('chatContent').style.display = 'flex';
    document.getElementById('notesContent').style.display = 'none';
    document.getElementById('inputArea').style.display = 'block';
}
function createNewNote() { showToast('success', 'Заметка создана', 'Новая заметка добавлена'); }
function selectNote(el) { document.querySelectorAll('.note-item').forEach(i => i.classList.remove('active')); el.classList.add('active'); }

// КНОПКИ СООБЩЕНИЙ
function copyMessage(btn) {
    const message = btn.closest('.message').querySelector('.message-bubble').innerText;
    navigator.clipboard.writeText(message);
    showToast('success', 'Скопировано', 'Сообщение скопировано в буфер обмена');
}
function editMessage(btn) {
    const bubble = btn.closest('.message').querySelector('.message-bubble');
    const newText = prompt('Редактировать сообщение:', bubble.innerText);
    if (newText) bubble.innerText = newText;
}
function deleteMessage(btn) {
    if (confirm('Удалить сообщение?')) {
        btn.closest('.message').remove();
        showToast('success', 'Удалено', 'Сообщение удалено');
    }
}
function regenerateMessage(btn) {
    const messageEl = btn.closest('.message');
    const bubble = messageEl.querySelector('.message-bubble');
    const originalText = bubble.innerHTML;
    bubble.innerHTML = '<em>🔄 Генерирую заново...</em>';
    showToast('success', 'Перегенерация', 'Запрос отправлен модели');
    setTimeout(() => {
        bubble.innerHTML = '<strong>Обновлённый вариант ответа:</strong><br><br>Текст был перегенерирован с учётом вашего запроса. Этот вариант содержит альтернативную формулировку и структуру ответа.';
        showToast('success', 'Готово', 'Сообщение перегенерировано');
    }, 1500);
}
function saveToNotes(btn) {
    const bubble = btn.closest('.message').querySelector('.message-bubble').innerText;
    btn.classList.toggle('active');
    showToast('success', 'Сохранено в заметки', 'Сообщение добавлено в раздел "Заметки"');
    setTimeout(() => btn.classList.remove('active'), 1000);
}
function saveToDB(btn) {
    btn.classList.toggle('active');
    showToast('success', 'Сохранено в БД', 'Данные записаны в базу данных проекта');
    setTimeout(() => btn.classList.remove('active'), 1000);
}

function sendMessage() {
    const input = document.getElementById('messageInput');
    const text = input.value.trim();
    if (!text) return;
    const messagesContainer = document.getElementById('chatMessages');
    const time = new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
    const messageHTML = `
        <div class="message user">
            <div class="message-avatar">👤</div>
            <div class="message-content">
                <div class="message-bubble">${text}</div>
                <div class="message-time">${time}</div>
            </div>
            <div class="message-actions">
                <button class="message-action-btn" title="Копировать" onclick="copyMessage(this)">📋</button>
                <button class="message-action-btn" title="Редактировать" onclick="editMessage(this)">✏️</button>
                <button class="message-action-btn" title="Удалить" onclick="deleteMessage(this)">🗑️</button>
            </div>
        </div>
    `;
    messagesContainer.insertAdjacentHTML('beforeend', messageHTML);
    input.value = '';
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
    setTimeout(() => {
        const aiHTML = `
            <div class="message ai">
                <div class="message-avatar">🤖</div>
                <div class="message-content">
                    <div class="message-bubble">Я получил ваше сообщение: "${text}". Чем могу помочь?</div>
                    <div class="message-time">${new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}</div>
                </div>
                <div class="message-actions">
                    <button class="message-action-btn" title="Копировать" onclick="copyMessage(this)"></button>
                    <button class="message-action-btn" title="Сгенерировать заново" onclick="regenerateMessage(this)">🔄</button>
                    <button class="message-action-btn" title="Сохранить в заметки" onclick="saveToNotes(this)">📝</button>
                    <button class="message-action-btn" title="Сохранить в БД" onclick="saveToDB(this)">💾</button>
                </div>
            </div>
        `;
        messagesContainer.insertAdjacentHTML('beforeend', aiHTML);
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }, 1000);
}

function showToast(type, title, message) {
    const container = document.getElementById('toastContainer');
    const icons = { success: '✓', error: '✕', warning: '' };
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `
        <div class="toast-icon">${icons[type]}</div>
        <div class="toast-content">
            <div class="toast-title">${title}</div>
            <div class="toast-message">${message}</div>
        </div>
        <button class="toast-close" onclick="this.parentElement.remove()">✕</button>
    `;
    container.appendChild(toast);
    setTimeout(() => {
        if (toast.parentElement) {
            toast.style.opacity = '0';
            toast.style.transform = 'translateX(100px)';
            setTimeout(() => toast.remove(), 300);
        }
    }, 4000);
}

const dropZone = document.getElementById('dropZone');
if (dropZone) {
    ['dragenter', 'dragover'].forEach(evt => { dropZone.addEventListener(evt, (e) => { e.preventDefault(); dropZone.classList.add('dragover'); }); });
    ['dragleave', 'drop'].forEach(evt => { dropZone.addEventListener(evt, (e) => { e.preventDefault(); dropZone.classList.remove('dragover'); }); });
    dropZone.addEventListener('drop', (e) => { showToast('success', 'Файлы добавлены', `${e.dataTransfer.files.length} файл(ов) готово к загрузке`); });
}

document.querySelectorAll('.theme-option').forEach(btn => {
    btn.addEventListener('click', function() {
        document.querySelectorAll('.theme-option').forEach(b => b.classList.remove('active'));
        this.classList.add('active');
        showToast('success', 'Тема изменена', `Применена тема: ${this.textContent.trim()}`);
    });
});

document.getElementById('messageInput')?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
});

setTimeout(() => {
    showToast('success', 'Добро пожаловать!', 'CorporateAgent v3.1 в стиле macOS готов к работе');
}, 500);
