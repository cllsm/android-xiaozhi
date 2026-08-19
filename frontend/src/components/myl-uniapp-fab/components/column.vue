<!--
 * @Date: 2025-04-07 15:12:47
 * @LastEditTimes: Do not edit
 * @Descripttion: describe
-->
<template>
  <view
    class="column-menu"
    :class="{ 'column-menu-active': isOpen }"
    :style="menuStyle"
    :data-direction="direction">
    <view
      v-for="(item, index) in menuItems"
      :key="index"
      class="menu-item"
      :class="{ 'menu-item-active': isOpen }"
      :style="{ transitionDelay: `${index * 0.05}s` }"
      @tap="handleSelect(item)">
      <slot name="menu-item" :data="item" :index="index">
        <text class="menu-icon">{{ item.icon }}</text>
        <text class="menu-text" v-if="item.text">{{ item.text }}</text>
      </slot>
    </view>
  </view>
</template>

<script setup>
import { defineProps, defineEmits, ref, watchEffect, onMounted } from "vue";
const props = defineProps({
  menuItems: {
    type: Array,
    required: true,
  },
  isOpen: {
    type: Boolean,
    default: false,
  },
  position: {
    type: Array,
    default: () => [10, 100],
  },
  direction: {
    type: String,
    default: "top",
    validator: (value) => ["top", "bottom", "left", "right"].includes(value),
  },
});

const direction = ref(props.direction);
const emit = defineEmits(["select"]);
const windowHeight = ref(0);
const windowWidth = ref(0);
console.log(props.menuItems, "menuItems");

onMounted(() => {
  const systemInfo = uni.getWindowInfo();
  windowHeight.value = systemInfo.windowHeight;
  windowWidth.value = systemInfo.windowWidth;
});

watchEffect(() => {
  direction.value = props.direction;
});

const computeDirection = () => {
  const [x, y] = props.position;
  const itemHeight = 80; // 每个菜单项的高度（包含间距）
  const itemWidth = 200; // 菜单项的宽度
  const menuPadding = 20; // 菜单的内边距
  const menuHeight = props.menuItems.length * itemHeight + menuPadding * 2;
  const menuWidth = itemWidth + menuPadding * 2;
  const safeDistance = 20; // 边界安全距离

  // 计算按钮位置相对于屏幕边界的距离
  const distanceToTop = y;
  const distanceToBottom = windowHeight.value - y;
  const distanceToLeft = x;
  const distanceToRight = windowWidth.value - x;

  // 根据按钮位置自动选择最优展开方向
  if (["top", "bottom"].includes(direction.value)) {
    if (distanceToTop < menuHeight + safeDistance) {
      direction.value = "bottom";
    } else if (
      distanceToBottom < menuHeight + safeDistance &&
      distanceToTop > distanceToBottom
    ) {
      direction.value = "top";
    }
  } else if (["left", "right"].includes(direction.value)) {
    if (
      distanceToLeft < menuWidth + safeDistance &&
      distanceToRight > distanceToLeft
    ) {
      direction.value = "right";
    } else if (
      distanceToRight < menuWidth + safeDistance &&
      distanceToLeft > distanceToRight
    ) {
      direction.value = "left";
    }
  }
};

watchEffect(() => {
  props.isOpen && computeDirection();
});

const handleSelect = (item) => {
  emit("select", item);
};
</script>

<style lang="scss" scoped>
.column-menu {
  position: fixed;
  // z-index: 999;
  display: flex;
  gap: 5px;
  justify-content: center;
  pointer-events: none;
  opacity: 0;
  transition: all 0.3s ease;

  &[data-direction="top"],
  &[data-direction="bottom"] {
    left: 18%;
    flex-direction: column;
    // transform: translate(0 -50%);
  }
  &[data-direction="top"] {
    bottom: 60px;
  }
  &[data-direction="bottom"] {
    top: 60px;
  }
  &[data-direction="left"],
  &[data-direction="right"] {
    flex-direction: row;
    transform: translate(0, -50%);
  }
  &[data-direction="left"] {
    top: 50%;
    right: 60px;
  }
  &[data-direction="right"] {
    top: 50%;
    left: 60px;
  }

  &.column-menu-active {
    pointer-events: auto;
    opacity: 1;
  }
}

.menu-item {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 10px;
  background-color: #ffffff;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.1);
  opacity: 0;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  transform: scale(0.5);

  &.menu-item-active {
    opacity: 1;
    transform: scale(1);
  }
}

.menu-icon {
  font-size: 20px;
}

.menu-text {
  font-size: 14px;
  color: #333;
}
</style>
