// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2014 Citra Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <cmath>
#include <mutex>
#include "core/frontend/emu_window.h"

namespace Core::Frontend {

EmuWindow::EmuWindow() {
    // TODO: Find a better place to set this.
    config.min_client_area_size =
        std::make_pair(Layout::MinimumSize::Width, Layout::MinimumSize::Height);
    active_config = config;
}

EmuWindow::~EmuWindow() {}

std::pair<f32, f32> EmuWindow::MapToTouchScreen(f32 framebuffer_x, f32 framebuffer_y) const {
    const auto clamp_coordinate = [](f32 value, u32 low, u32 high) -> u32 {
        if (!std::isfinite(value)) {
            return low;
        }
        if (high <= low) {
            return low;
        }
        return static_cast<u32>(
            std::clamp(value, static_cast<f32>(low), static_cast<f32>(high)));
    };

    const auto& screen = framebuffer_layout.screen;
    const u32 x = clamp_coordinate(framebuffer_x, screen.left,
                                   screen.right > screen.left ? screen.right - 1 : screen.left);
    const u32 y = clamp_coordinate(framebuffer_y, screen.top,
                                   screen.bottom > screen.top ? screen.bottom - 1 : screen.top);
    const float normalized_x =
        static_cast<float>(x - screen.left) / static_cast<float>(screen.right - screen.left);
    const float normalized_y =
        static_cast<float>(y - screen.top) / static_cast<float>(screen.bottom - screen.top);

    return std::make_pair(normalized_x, normalized_y);
}

std::pair<u32, u32> EmuWindow::ClipToTouchScreen(u32 new_x, u32 new_y) const {
    new_x = (std::max)(new_x, framebuffer_layout.screen.left);
    new_x = (std::min)(new_x, framebuffer_layout.screen.right - 1);

    new_y = (std::max)(new_y, framebuffer_layout.screen.top);
    new_y = (std::min)(new_y, framebuffer_layout.screen.bottom - 1);

    return std::make_pair(new_x, new_y);
}

void EmuWindow::UpdateCurrentFramebufferLayout(u32 width, u32 height) {
    NotifyFramebufferLayoutChanged(Layout::DefaultFrameLayout(width, height));
}

} // namespace Core::Frontend
