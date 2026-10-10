"""TrackNet (the tracking module of TrackNetV3), ported from the official code.

Source: https://github.com/qaz812345/TrackNetV3 (model.py at 6eda442), MIT licence,
Copyright (c) the TrackNetV3 authors. Layer names are kept identical so the official checkpoint
loads with strict key matching.
"""

from __future__ import annotations

import torch
from torch import nn


class Conv2DBlock(nn.Module):
    """Conv2D + BN + ReLU."""

    def __init__(self, in_dim: int, out_dim: int):
        super().__init__()
        self.conv = nn.Conv2d(in_dim, out_dim, kernel_size=3, padding="same", bias=False)
        self.bn = nn.BatchNorm2d(out_dim)
        self.relu = nn.ReLU()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.relu(self.bn(self.conv(x)))


class Double2DConv(nn.Module):
    def __init__(self, in_dim: int, out_dim: int):
        super().__init__()
        self.conv_1 = Conv2DBlock(in_dim, out_dim)
        self.conv_2 = Conv2DBlock(out_dim, out_dim)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.conv_2(self.conv_1(x))


class Triple2DConv(nn.Module):
    def __init__(self, in_dim: int, out_dim: int):
        super().__init__()
        self.conv_1 = Conv2DBlock(in_dim, out_dim)
        self.conv_2 = Conv2DBlock(out_dim, out_dim)
        self.conv_3 = Conv2DBlock(out_dim, out_dim)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.conv_3(self.conv_2(self.conv_1(x)))


class TrackNet(nn.Module):
    """U-Net: (N, in_dim, 288, 512) frames -> (N, out_dim, 288, 512) shuttle heatmaps."""

    def __init__(self, in_dim: int, out_dim: int):
        super().__init__()
        self.down_block_1 = Double2DConv(in_dim, 64)
        self.down_block_2 = Double2DConv(64, 128)
        self.down_block_3 = Triple2DConv(128, 256)
        self.bottleneck = Triple2DConv(256, 512)
        self.up_block_1 = Triple2DConv(768, 256)
        self.up_block_2 = Double2DConv(384, 128)
        self.up_block_3 = Double2DConv(192, 64)
        self.predictor = nn.Conv2d(64, out_dim, (1, 1))
        self.sigmoid = nn.Sigmoid()
        self.pool = nn.MaxPool2d((2, 2), stride=(2, 2))
        self.up = nn.Upsample(scale_factor=2)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x1 = self.down_block_1(x)
        x2 = self.down_block_2(self.pool(x1))
        x3 = self.down_block_3(self.pool(x2))
        x = self.bottleneck(self.pool(x3))
        x = self.up_block_1(torch.cat([self.up(x), x3], dim=1))
        x = self.up_block_2(torch.cat([self.up(x), x2], dim=1))
        x = self.up_block_3(torch.cat([self.up(x), x1], dim=1))
        return self.sigmoid(self.predictor(x))


def from_checkpoint(ckpt: dict) -> tuple[TrackNet, int]:
    """Build TrackNet from an official checkpoint dict; returns (model, seq_len).

    Only bg_mode "concat" is supported (the released checkpoint): input is the background median
    plus seq_len RGB frames, (seq_len + 1) * 3 channels.
    """
    params = ckpt["param_dict"]
    seq_len, bg_mode = int(params["seq_len"]), params["bg_mode"]
    if bg_mode != "concat":
        raise ValueError(f"unsupported bg_mode {bg_mode!r}; the released checkpoint uses concat")
    model = TrackNet(in_dim=(seq_len + 1) * 3, out_dim=seq_len)
    model.load_state_dict(ckpt["model"], strict=True)
    return model.eval(), seq_len
