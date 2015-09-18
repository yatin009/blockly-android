/*
* Copyright  2015 Google Inc. All Rights Reserved.
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.google.blockly.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.FrameLayout;

import com.google.blockly.R;
import com.google.blockly.model.Block;
import com.google.blockly.model.Input;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a block and handles laying out all its inputs/fields.
 */
public class BlockView extends FrameLayout {
    private static final String TAG = "BlockView";

    // TODO: Replace these with dimens so they get scaled correctly
    // Minimum height of a block should be the same as an empty field.
    private static final int BASE_HEIGHT = InputView.BASE_HEIGHT;
    // Minimum width of a block should be the same as an empty.
    private static final int BASE_WIDTH = InputView.BASE_WIDTH;

    // Color of block outline.
    private static final int OUTLINE_COLOR = Color.BLACK;

    private final WorkspaceHelper mHelper;
    private final Block mBlock;

    // Objects for drawing the block.
    private final Path mDrawPath = new Path();
    private final Paint mPaintArea = new Paint();
    private final Paint mPaintBorder = new Paint();
    private final ArrayList<InputView> mInputViews = new ArrayList<>();

    // Current measured size of this block view.
    private final ViewPoint mBlockViewSize = new ViewPoint();
    private ArrayList<ViewPoint> mInputLayoutOrigins = new ArrayList<>();
    private BlockWorkspaceParams mWorkspaceParams;

    // Offset of the block origin inside the view's measured area.
    private int mLayoutMarginLeft;
    private int mMaxRowWidth;

    // Vertical offset for positioning the "Next" block (if one exists).
    private int mNextBlockVerticalOffset;

    // Width of the core "block", ie, rectangle box without connectors or inputs.
    private int mBlockWidth;

    /**
     * Create a new BlockView for the given block using the workspace's style.
     *
     * @param context The context for creating this view.
     * @param block   The block represented by this view.
     * @param helper  The helper for loading workspace configs and doing calculations.
     */
    public BlockView(Context context, Block block, WorkspaceHelper helper) {
        this(context, 0 /* default style */, block, helper);
    }

    /**
     * Create a new BlockView for the given block using the specified style. The style must extend
     * {@link R.style#DefaultBlockStyle}.
     *
     * @param context    The context for creating this view.
     * @param blockStyle The resource id for the style to use on this view.
     * @param block      The block represented by this view.
     * @param helper     The helper for loading workspace configs and doing calculations.
     */
    public BlockView(Context context, int blockStyle, Block block, WorkspaceHelper helper) {
        super(context, null, 0);

        mBlock = block;
        mHelper = helper;
        mWorkspaceParams = new BlockWorkspaceParams(mBlock, mHelper);

        setWillNotDraw(false);

        initViews(context, blockStyle);
        initDrawingObjects(context);
    }

    /**
     * @return The {@link InputView} for the {@link Input} at the given index.
     */
    public InputView getInputView(int index) {
        return mInputViews.get(index);
    }

    @Override
    public void onDraw(Canvas c) {
        c.drawPath(mDrawPath, mPaintArea);
        c.drawPath(mDrawPath, mPaintBorder);
    }

    /**
     * Measure all children (i.e., block inputs) and compute their sizes and relative positions
     * for use in {@link #onLayout}.
     */
    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        adjustInputLayoutOriginsListSize();

        if (getBlock().getInputsInline()) {
            onMeasureInlineInputs(widthMeasureSpec, heightMeasureSpec);
        } else {
            onMeasureExternalInputs(widthMeasureSpec, heightMeasureSpec);
        }

        mNextBlockVerticalOffset = mBlockViewSize.y;
        if (mBlock.getNextConnection() != null) {
            mBlockViewSize.y += ConnectorHelper.CONNECTOR_SIZE_PERPENDICULAR;
        }

        if (mBlock.getOutputConnection() != null) {
            mLayoutMarginLeft = ConnectorHelper.CONNECTOR_SIZE_PERPENDICULAR;
            mBlockViewSize.x += mLayoutMarginLeft;
        } else {
            mLayoutMarginLeft = 0;
        }

        setMeasuredDimension(mBlockViewSize.x, mBlockViewSize.y);
        mWorkspaceParams.setMeasuredDimensions(mBlockViewSize);
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (getBlock().getInputsInline()) {
            onLayoutInlineInputs(changed, left, top, right, bottom);
        } else {
            onLayoutExternalInputs(changed, left, top, right, bottom);
        }
    }

    /**
     * @return The block for this view.
     */
    public Block getBlock() {
        return mBlock;
    }

    /**
     * Measure view and its children with inline inputs.
     * <p>
     * This function does not return a value but has the following side effects. Upon return:
     * <ol>
     * <li>The {@link InputView#measure(int, int)} method has been called for all inputs in
     * this block,</li>
     * <li>{@link #mBlockViewSize} contains the size of the total size of the block view
     * including all its inputs, and</li>
     * <li>{@link #mInputLayoutOrigins} contains the layout positions of all inputs within
     * the block.</li>
     * </ol>
     * </p>
     */
    private void onMeasureInlineInputs(int widthMeasureSpec, int heightMeasureSpec) {
        int rowLeft = 0;
        int rowTop = 0;

        int rowHeight = 0;
        int maxRowWidth = 0;

        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            inputView.measureFieldsAndInputs(widthMeasureSpec, heightMeasureSpec);
            inputView.measure(widthMeasureSpec, heightMeasureSpec);

            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                rowTop += rowHeight;
                rowHeight = 0;
                rowLeft = 0;
            }

            mInputLayoutOrigins.get(i).set(rowLeft, rowTop);

            rowHeight = Math.max(rowHeight, inputView.getMeasuredHeight());
            rowLeft += inputView.getMeasuredWidth();

            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                // The block width is that of the widest row, but for a Statement input there needs
                // to be added space for the connector.
                maxRowWidth = Math.max(
                        maxRowWidth, rowLeft + ConnectorHelper.STATEMENT_INPUT_MINIMUM_WIDTH);

                // Statement input is always a row by itself, so increase top coordinate and reset
                // row origin and height.
                rowLeft = 0;
                rowTop += rowHeight;
                rowHeight = 0;
            } else {
                // For Dummy and Value inputs, block width is that of the widest row
                maxRowWidth = Math.max(maxRowWidth, rowLeft);
            }
        }

        // Add height of final row. This is non-zero with inline inputs if the final input in the
        // block is not a Statement input.
        rowTop += rowHeight;

        // Block width is the computed width of the widest input row (at least BASE_WIDTH).
        mBlockViewSize.x = Math.max(maxRowWidth, BASE_WIDTH);
        mBlockWidth = mBlockViewSize.x;

        // Height is vertical position of next (non-existent) inputs row plus bottom padding plus
        // room for extruding "Next" connector. Also must be at least the base height.
        mBlockViewSize.y = Math.max(BASE_HEIGHT, rowTop);
    }

    /**
     * Measure view and its children with external inputs.
     * <p>
     * This function does not return a value but has the following side effects. Upon return:
     * <ol>
     * <li>The {@link InputView#measure(int, int)} method has been called for all inputs in
     * this block,</li>
     * <li>{@link #mBlockViewSize} contains the size of the total size of the block view
     * including all its inputs, and</li>
     * <li>{@link #mInputLayoutOrigins} contains the layout positions of all inputs within
     * the block (but note that for external inputs, only the y coordinate of each
     * position is later used for positioning.)</li>
     * </ol>
     * </p>
     */
    private void onMeasureExternalInputs(int widthMeasureSpec, int heightMeasureSpec) {
        mMaxRowWidth = BASE_WIDTH;
        int maxChildWidth = 0;

        // First pass - measure fields and children of all inputs.
        boolean hasValueInput = false;
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            inputView.measureFieldsAndInputs(widthMeasureSpec, heightMeasureSpec);
            mMaxRowWidth = Math.max(mMaxRowWidth, inputView.getTotalFieldWidth());
            maxChildWidth = Math.max(maxChildWidth, inputView.getTotalChildWidth());

            if (inputView.getInput().getType() == Input.TYPE_VALUE) {
                hasValueInput = true;
            }
        }

        // Second pass - force all inputs to render fields with the same width and compute positions
        // for all inputs.
        int rowTop = 0;
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            if (inputView.getInput().getType() != Input.TYPE_STATEMENT) {
                inputView.setFieldLayoutWidth(mMaxRowWidth);
            }
            inputView.measure(widthMeasureSpec, heightMeasureSpec);

            mInputLayoutOrigins.get(i).set(0, rowTop);

            // The block height is the sum of all the row heights.
            rowTop += inputView.getMeasuredHeight();
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                rowTop += ConnectorHelper.STATEMENT_INPUT_BOTTOM_HEIGHT;
            }
        }

        // Block width is the width of the longest row. Add space for connector if there is at least
        // one Value input.
        mBlockWidth = mMaxRowWidth;
        if (hasValueInput) {
            mBlockWidth += ConnectorHelper.CONNECTOR_SIZE_PERPENDICULAR;
        }

        // The width of the block view is the width of the block plus the maximum width of any of
        // its children. If there are no children, make sure view is at least as wide as the Block,
        // which accounts for width of unconnected input puts.
        mBlockViewSize.x = Math.max(mBlockWidth, mMaxRowWidth + maxChildWidth);
        mBlockViewSize.y = Math.max(BASE_HEIGHT, rowTop);
    }

    private void onLayoutInlineInputs(boolean changed, int left, int top, int right, int bottom) {
        int xLeft = mLayoutMarginLeft;
        for (int i = 0; i < mInputViews.size(); i++) {
            int rowLeft = xLeft + mInputLayoutOrigins.get(i).x;
            int rowTop = mInputLayoutOrigins.get(i).y;
            InputView inputView = mInputViews.get(i);

            switch (inputView.getInput().getType()) {
                default:
                case Input.TYPE_DUMMY:
                case Input.TYPE_VALUE: {
                    // Inline Dummy and Value inputs are drawn at their position as computed in
                    // onMeasure().
                    inputView.layout(rowLeft, rowTop, rowLeft + inputView.getMeasuredWidth(),
                            rowTop + inputView.getMeasuredHeight());
                    break;
                }
                case Input.TYPE_STATEMENT: {
                    // Statement inputs are always left-aligned with the block boundary.
                    // Effectively, they are also centered, since the width of the rendered
                    // block is adjusted to match their exact width.)
                    inputView.layout(xLeft, rowTop, xLeft + inputView.getMeasuredWidth(),
                            rowTop + inputView.getMeasuredHeight());
                    break;
                }
            }
        }
    }

    private void onLayoutExternalInputs(boolean changed, int left, int top, int right, int bottom) {
        int xLeft = mLayoutMarginLeft;
        int xRight = mMaxRowWidth;
        for (int i = 0; i < mInputViews.size(); i++) {
            int rowTop = mInputLayoutOrigins.get(i).y;
            InputView inputView = mInputViews.get(i);

            switch (inputView.getInput().getType()) {
                default:
                case Input.TYPE_DUMMY:
                case Input.TYPE_VALUE: {
                    inputView.layout(xLeft, rowTop, xLeft + inputView.getMeasuredWidth(),
                            rowTop + inputView.getMeasuredHeight());
                    break;
                }
                case Input.TYPE_STATEMENT: {
                    // Statement inputs are always left-aligned with the block boundary.
                    // Effectively, they are also centered, since the width of the rendered
                    // block is adjusted to match their exact width.)
                    inputView.layout(xLeft, rowTop, xLeft + inputView.getMeasuredWidth(),
                            rowTop + inputView.getMeasuredHeight());
                    break;
                }
            }
        }
    }

    /**
     * A block is responsible for initializing all of its fields. Sub-blocks must be added
     * elsewhere.
     */
    private void initViews(Context context, int blockStyle) {
        List<Input> inputs = mBlock.getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            InputView inputView = new InputView(context, blockStyle, inputs.get(i), mHelper);
            mInputViews.add(inputView);
            addView(inputView);
        }
    }

    private void initDrawingObjects(Context context) {
        mPaintArea.setColor(mBlock.getColour());
        mPaintArea.setStyle(Paint.Style.FILL);
        mPaintArea.setStrokeJoin(Paint.Join.ROUND);

        mPaintBorder.setColor(OUTLINE_COLOR);
        mPaintBorder.setStyle(Paint.Style.STROKE);
        mPaintBorder.setStrokeWidth(1);
        mPaintBorder.setStrokeJoin(Paint.Join.ROUND);

        mDrawPath.setFillType(Path.FillType.EVEN_ODD);
    }

    /**
     * Adjust size of {@link #mInputLayoutOrigins} list to match the size of {@link #mInputViews}.
     */
    private void adjustInputLayoutOriginsListSize() {
        if (mInputLayoutOrigins.size() != mInputViews.size()) {
            mInputLayoutOrigins.ensureCapacity(mInputViews.size());
            if (mInputLayoutOrigins.size() < mInputViews.size()) {
                for (int i = mInputLayoutOrigins.size(); i < mInputViews.size(); i++) {
                    mInputLayoutOrigins.add(new ViewPoint());
                }
            } else {
                while (mInputLayoutOrigins.size() > mInputViews.size()) {
                    mInputLayoutOrigins.remove(mInputLayoutOrigins.size() - 1);
                }
            }
        }
    }

    /**
     * @return Vertical offset for positioning the "Next" block (if one exists). This is relative to
     * the top of this view's area.
     */
    int getNextBlockVerticalOffset() {
        return mNextBlockVerticalOffset;
    }

    /**
     * Update path for drawing the block after view size has changed.
     *
     * @param width  The new width of the block view.
     * @param height The new height of the block view.
     * @param oldw   The previous width of the block view (unused).
     * @param oldh   The previous height of the block view (unused).
     */
    @Override
    protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        mDrawPath.reset();

        int xLeft = mLayoutMarginLeft;
        int xRight = mBlockWidth + mLayoutMarginLeft;

        int yTop = 0;
        int yBottom = mNextBlockVerticalOffset;

        // Top of the block, including "Previous" connector.
        mDrawPath.moveTo(xLeft, yTop);
        if (mBlock.getPreviousConnection() != null) {
            ConnectorHelper.addPreviousConnectorToPath(mDrawPath, xLeft, yTop);
        }
        mDrawPath.lineTo(xRight, yTop);

        // Right-hand side of the block, including "Input" connectors.
        // TODO(rohlfingt): draw this on the opposite side in RTL mode.
        for (int i = 0; i < mInputViews.size(); ++i) {
            InputView inputView = mInputViews.get(i);
            ViewPoint inputLayoutOrigin = mInputLayoutOrigins.get(i);
            switch (inputView.getInput().getType()) {
                default:
                case Input.TYPE_DUMMY: {
                    break;
                }
                case Input.TYPE_VALUE: {
                    if (!getBlock().getInputsInline()) {
                        ConnectorHelper.addValueInputConnectorToPath(
                                mDrawPath, xRight, inputLayoutOrigin.y);
                    }
                    break;
                }
                case Input.TYPE_STATEMENT: {
                    int xOffset = xLeft + inputView.getTotalFieldWidth();
                    int connectorHeight = inputView.getTotalChildHeight();
                    ConnectorHelper.addStatementInputConnectorToPath(
                            mDrawPath, xRight, inputLayoutOrigin.y, xOffset, connectorHeight);
                    break;
                }
            }
        }
        mDrawPath.lineTo(xRight, yBottom);

        // Bottom of the block, including "Next" connector.
        if (mBlock.getNextConnection() != null) {
            ConnectorHelper.addNextConnectorToPath(mDrawPath, xLeft, yBottom);
        }
        mDrawPath.lineTo(xLeft, yBottom);

        // Left-hand side of the block, including "Output" connector.
        if (mBlock.getOutputConnection() != null) {
            ConnectorHelper.addOutputConnectorToPath(mDrawPath, xLeft, yTop);
        }
        mDrawPath.lineTo(xLeft, yTop);
        // Draw an additional line segment over again to get a final rounded corner.
        mDrawPath.lineTo(xLeft + ConnectorHelper.CONNECTOR_OFFSET, yTop);

        // Add cutout paths for "holes" from open inline Value inputs.
        if (getBlock().getInputsInline()) {
            for (int i = 0; i < mInputViews.size(); ++i) {
                InputView inputView = mInputViews.get(i);
                if (inputView.getInput().getType() == Input.TYPE_VALUE) {
                    ViewPoint inputLayoutOrigin = mInputLayoutOrigins.get(i);
                    inputView.addInlineCutoutToBlockViewPath(mDrawPath,
                            xLeft + inputLayoutOrigin.x, inputLayoutOrigin.y);
                }
            }
        }

        mDrawPath.close();
    }
}
